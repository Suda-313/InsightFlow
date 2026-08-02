package com.insightflow.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.common.exception.ImportFileNotFoundException;
import com.insightflow.common.exception.ImportValidationException;
import com.insightflow.dto.importing.ImportMapping;
import com.insightflow.dto.importing.ImportTaskPayload;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import com.insightflow.service.WorkspaceService;
import com.insightflow.entity.Workspace;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 受理导入命令并把文件状态、映射快照和异步任务放进同一个数据库事务。
 *
 * <p>该类独立于 HTTP 服务，后续若唯一键冲突导致其事务回滚，外层服务可以在新事务中读取已由
 * 并发请求创建的任务，而不会在 PostgreSQL 已中止的事务内继续查询。</p>
 */
@Service
public class ImportTaskCommandService {

    /** 工作区服务先解析公开 UUID，所有后续仓储查询都使用其内部隔离键。 */
    private final WorkspaceService workspaceService;

    /** 文件仓储提供悲观行锁，串行化同一文件的映射冻结和启动操作。 */
    private final ImportFileRepository importFileRepository;

    /** 任务仓储负责幂等键查询和唯一约束这一最终并发边界。 */
    private final AsyncTaskRepository taskRepository;

    /** JSON 工具将已经验证的映射复制到不可变任务 payload。 */
    private final ObjectMapper objectMapper;

    /** 调度器只在数据库事务提交后被唤醒，实际恢复仍以持久化扫描为准。 */
    private final ImportTaskScheduler importTaskScheduler;

    /** 构造命令服务，所有跨层依赖显式注入以便后续替换为受控任务执行器。 */
    public ImportTaskCommandService(
            WorkspaceService workspaceService,
            ImportFileRepository importFileRepository,
            AsyncTaskRepository taskRepository,
            ObjectMapper objectMapper,
            ImportTaskScheduler importTaskScheduler) {
        this.workspaceService = workspaceService;
        this.importFileRepository = importFileRepository;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.importTaskScheduler = importTaskScheduler;
    }

    /**
     * 在锁定文件后创建任务；若幂等任务已存在则直接返回，不允许第二个任务更改该文件的执行输入。
     */
    @Transactional
    public AsyncTask start(UUID workspacePublicId, UUID filePublicId, String idempotencyKey) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        ImportFile file = importFileRepository.findByWorkspaceIdAndPublicIdForUpdate(workspace.getId(), filePublicId)
                .orElseThrow(() -> new ImportFileNotFoundException(filePublicId));
        String payload = writePayload(file);
        AsyncTask existing = taskRepository
                .findByWorkspaceIdAndTaskTypeAndIdempotencyKey(workspace.getId(), "import", idempotencyKey)
                .orElse(null);
        if (existing != null) {
            return ensureSameCommand(existing, payload);
        }
        if ("processing".equals(file.getStatus())) {
            return taskRepository.findFirstByWorkspaceIdAndImportFileIdOrderByCreatedAtDesc(workspace.getId(), file.getId())
                    .orElseThrow(() -> new ImportValidationException("导入任务状态不一致，请稍后重试。"));
        }
        if (!"mapped".equals(file.getStatus())) {
            throw new ImportValidationException("只有映射校验完成的文件可以开始导入。");
        }
        file.markProcessing();
        AsyncTask task = taskRepository.saveAndFlush(AsyncTask.queuedImport(
                workspace.getId(), file.getId(), idempotencyKey, payload));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 提交完成才唤醒调度器，避免 Worker 读取未提交的文件状态或任务 payload。 */
            @Override
            public void afterCommit() {
                importTaskScheduler.dispatchClaimableTasks();
            }
        });
        return task;
    }

    /**
     * 唯一索引冲突后的新事务补偿读取；PostgreSQL 约束异常会中止旧事务，因此不能在原事务继续处理。
     */
    @Transactional(readOnly = true)
    public AsyncTask resolveIdempotencyCollision(UUID workspacePublicId, UUID filePublicId, String idempotencyKey) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        ImportFile file = importFileRepository.findByWorkspaceIdAndPublicId(workspace.getId(), filePublicId)
                .orElseThrow(() -> new ImportFileNotFoundException(filePublicId));
        AsyncTask existing = taskRepository
                .findByWorkspaceIdAndTaskTypeAndIdempotencyKey(workspace.getId(), "import", idempotencyKey)
                .orElseThrow(() -> new ImportValidationException("并发创建导入任务失败，请使用新的 Idempotency-Key 重试。"));
        return ensureSameCommand(existing, writePayload(file));
    }

    /** 将文件中已验证的映射读出并写入任务输入；未映射文件绝不能构造半成品任务。 */
    private String writePayload(ImportFile file) {
        if (file.getMappingJson() == null) {
            throw new ImportValidationException("导入映射不存在。");
        }
        try {
            ImportMapping mapping = objectMapper.readValue(file.getMappingJson(), ImportMapping.class);
            return objectMapper.writeValueAsString(new ImportTaskPayload(file.getPublicId(), mapping));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist import task payload", exception);
        }
    }

    /** 相同幂等键必须表达完全相同的冻结输入，防止客户端错误复用命令键。 */
    private AsyncTask ensureSameCommand(AsyncTask existing, String requestedPayload) {
        try {
            if (!objectMapper.readTree(existing.getPayloadJson()).equals(objectMapper.readTree(requestedPayload))) {
                throw new ImportValidationException("同一 Idempotency-Key 不能用于不同导入文件或映射。");
            }
            return existing;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored import task payload is invalid", exception);
        }
    }
}
