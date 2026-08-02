package com.insightflow.task;

import com.insightflow.entity.ImportFile;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.repository.FeedbackProjectionAnnotationRepository;
import com.insightflow.repository.ImportFileRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 应用就绪后扫描「已标记 projected 但 L2 标注为空」的工作区并自动重投影。
 *
 * <p>典型成因：旧字节码只写 L1 即 succeeded，或进程未加载含 L2 管线的 class。恢复步骤为
 * 清事实 → 拆旧 async_task / workspace_projection → 将 import_file 置 pending，
 * 由 {@link WorkspaceProjectionScheduler} 在事务提交后领取新任务。</p>
 */
@Component
public class IncompleteProjectionRecovery {

    private static final Logger log = LoggerFactory.getLogger(IncompleteProjectionRecovery.class);

    private final FeedbackEventRepository feedbackEventRepository;
    private final FeedbackProjectionAnnotationRepository annotationRepository;
    private final ImportFileRepository importFileRepository;
    private final AsyncTaskRepository taskRepository;
    private final ProjectionRequeueSupport requeueSupport;
    private final WorkspaceProjectionScheduler scheduler;
    private final String ruleVersion;

    public IncompleteProjectionRecovery(
            FeedbackEventRepository feedbackEventRepository,
            FeedbackProjectionAnnotationRepository annotationRepository,
            ImportFileRepository importFileRepository,
            AsyncTaskRepository taskRepository,
            ProjectionRequeueSupport requeueSupport,
            WorkspaceProjectionScheduler scheduler,
            @org.springframework.beans.factory.annotation.Value("${insightflow.projection.rule-version:rules:v1}") String ruleVersion) {
        this.feedbackEventRepository = feedbackEventRepository;
        this.annotationRepository = annotationRepository;
        this.importFileRepository = importFileRepository;
        this.taskRepository = taskRepository;
        this.requeueSupport = requeueSupport;
        this.scheduler = scheduler;
        this.ruleVersion = ruleVersion;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverOnStartup() {
        boolean anyRecovered = false;
        anyRecovered |= recoverFiles(importFileRepository.findByProjectionStatusAndStatus("projected", "processed"));
        // 守卫失败后的 projection_failed 也需重入队，否则用户只能手工改库。
        anyRecovered |= recoverFiles(importFileRepository.findByProjectionStatusAndStatus("projection_failed", "processed"));
        if (anyRecovered) {
            dispatchAfterCommit();
        }
    }

    private boolean recoverFiles(List<ImportFile> files) {
        boolean anyRecovered = false;
        for (ImportFile file : files) {
            if (!needsRecovery(file.getWorkspaceId())) {
                continue;
            }
            recoverWorkspaceFile(file);
            anyRecovered = true;
        }
        return anyRecovered;
    }

    /** 有反馈事件、无 L2 标注且文件已 projected，视为半完成投影。 */
    private boolean needsRecovery(Long workspaceId) {
        long eventCount = feedbackEventRepository.countByWorkspaceId(workspaceId);
        long annotationCount = annotationRepository.countByWorkspaceId(workspaceId);
        return eventCount > 0 && annotationCount == 0;
    }

    private void recoverWorkspaceFile(ImportFile file) {
        log.warn(
                "检测到工作区 {} 文件 {} 投影不完整（有反馈无 L2），清理后重入队",
                file.getWorkspaceId(),
                file.getId());
        String idempotencyKey = "projection:file:" + file.getId() + ":" + ruleVersion;
        taskRepository
                .findByWorkspaceIdAndTaskTypeAndIdempotencyKey(file.getWorkspaceId(), "projection", idempotencyKey)
                .ifPresent(task -> {
                    requeueSupport.removeProjectionChain(file.getWorkspaceId(), task);
                });
        requeueSupport.wipeAnalysisFacts(file.getWorkspaceId());
        file.markProjectionPending();
        importFileRepository.save(file);
    }

    private void dispatchAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            scheduler.dispatchClaimableTasks();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                scheduler.dispatchClaimableTasks();
            }
        });
    }
}
