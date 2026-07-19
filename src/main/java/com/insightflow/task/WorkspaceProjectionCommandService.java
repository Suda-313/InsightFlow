package com.insightflow.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.common.exception.ImportValidationException;
import com.insightflow.dto.analysis.ProjectionTaskPayload;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.entity.ProjectionFile;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import com.insightflow.repository.ProjectionFileRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 将已成功导入的文件转换为幂等的自动看板投影命令。
 *
 * <p>该服务只冻结输入和创建持久化任务；它不读取反馈、不执行主题规则，也不写指标或 Alert。实际执行
 * 由独立 Worker 负责，保证 CSV 导入完成事务不会被看板任务拖慢。</p>
 */
@Service
public class WorkspaceProjectionCommandService {

    /** 文件仓储以行锁串行化同一文件的投影命令创建。 */
    private final ImportFileRepository importFileRepository;

    /** 通用任务仓储提供命令幂等键和后续租约状态机。 */
    private final AsyncTaskRepository taskRepository;

    /** 投影记录保存规则版本和用户可见的状态快照。 */
    private final WorkspaceProjectionRepository projectionRepository;

    /** 来源文件关联保存冻结输入，后续多文件投影可以复用相同结构。 */
    private final ProjectionFileRepository projectionFileRepository;

    /** JSON 工具只序列化受控 payload，不保存原始 CSV 内容。 */
    private final ObjectMapper objectMapper;

    /** 提交后唤醒调度器；数据库扫描仍是崩溃恢复的权威路径。 */
    private final WorkspaceProjectionScheduler scheduler;

    /** 当前规则版本作为可追溯任务输入，主题规则尚未在本阶段执行。 */
    private final String ruleVersion;

    /** 构造自动投影命令服务。 */
    public WorkspaceProjectionCommandService(
            ImportFileRepository importFileRepository,
            AsyncTaskRepository taskRepository,
            WorkspaceProjectionRepository projectionRepository,
            ProjectionFileRepository projectionFileRepository,
            ObjectMapper objectMapper,
            WorkspaceProjectionScheduler scheduler,
            @org.springframework.beans.factory.annotation.Value("${insightflow.projection.rule-version:rules:v1}") String ruleVersion) {
        this.importFileRepository = importFileRepository;
        this.taskRepository = taskRepository;
        this.projectionRepository = projectionRepository;
        this.projectionFileRepository = projectionFileRepository;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
        this.ruleVersion = ruleVersion;
    }

    /**
     * 为成功导入文件创建一次且仅一次投影任务；重复调用返回同一任务，不重复计算或重复推进文件状态。
     */
    @Transactional
    public AsyncTask enqueueForImportedFile(Long workspaceId, Long importFileId) {
        ImportFile file = importFileRepository.findByIdAndWorkspaceIdForUpdate(importFileId, workspaceId)
                .orElseThrow(() -> new ImportValidationException("导入文件不存在或不属于当前工作区。"));
        if (!"processed".equals(file.getStatus())) {
            throw new ImportValidationException("只有成功导入的文件可以进入看板投影。");
        }
        String idempotencyKey = "projection:file:" + file.getId() + ":" + ruleVersion;
        AsyncTask existing = taskRepository
                .findByWorkspaceIdAndTaskTypeAndIdempotencyKey(workspaceId, "projection", idempotencyKey)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        String payload = writePayload(file);
        AsyncTask task = taskRepository.saveAndFlush(AsyncTask.queuedProjection(workspaceId, idempotencyKey, payload));
        WorkspaceProjection projection = projectionRepository.saveAndFlush(
                WorkspaceProjection.queued(workspaceId, task.getId(), ruleVersion));
        projectionFileRepository.saveAndFlush(ProjectionFile.of(projection.getId(), workspaceId, file.getId()));
        file.markProjectionPending();
        dispatchAfterCommit();
        return task;
    }

    /** 将公开文件 UUID 和规则版本冻结到任务 payload，避免 Worker 依赖可变页面状态。 */
    private String writePayload(ImportFile file) {
        try {
            return objectMapper.writeValueAsString(new ProjectionTaskPayload(List.of(file.getPublicId()), ruleVersion));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize projection task payload", exception);
        }
    }

    /** 在真实事务提交后才唤醒 Worker；纯单元测试没有事务同步时允许直接验证调度请求。 */
    private void dispatchAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            scheduler.dispatchClaimableTasks();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 只有任务、投影记录和来源文件关联均提交成功后，Worker 才能读取它们。 */
            @Override
            public void afterCommit() {
                scheduler.dispatchClaimableTasks();
            }
        });
    }
}
