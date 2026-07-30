package com.insightflow.task;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackProjectionAnnotationRepository;
import com.insightflow.repository.ProjectionFileRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import com.insightflow.service.analysis.ProjectionFactWiper;
import org.springframework.stereotype.Component;

/**
 * 半完成投影的清理与重入队辅助。
 *
 * <p>旧版 Worker 可能只写 L1 / data_cell 即标记 succeeded，且 {@code async_task.idempotency_key}
 * 会阻止调度器再次创建投影任务。本类在检测到「任务已成功但 L2 标注缺失」时拆除旧任务链并清事实，
 * 让 {@link WorkspaceProjectionCommandService} 与启动恢复逻辑可以安全重跑完整投影。</p>
 */
@Component
public class ProjectionRequeueSupport {

    private final AsyncTaskRepository taskRepository;
    private final WorkspaceProjectionRepository projectionRepository;
    private final ProjectionFileRepository projectionFileRepository;
    private final FeedbackProjectionAnnotationRepository annotationRepository;
    private final DataCellRepository dataCellRepository;
    private final ProjectionFactWiper projectionFactWiper;

    public ProjectionRequeueSupport(
            AsyncTaskRepository taskRepository,
            WorkspaceProjectionRepository projectionRepository,
            ProjectionFileRepository projectionFileRepository,
            FeedbackProjectionAnnotationRepository annotationRepository,
            DataCellRepository dataCellRepository,
            ProjectionFactWiper projectionFactWiper) {
        this.taskRepository = taskRepository;
        this.projectionRepository = projectionRepository;
        this.projectionFileRepository = projectionFileRepository;
        this.annotationRepository = annotationRepository;
        this.dataCellRepository = dataCellRepository;
        this.projectionFactWiper = projectionFactWiper;
    }

    /**
     * 是否可复用既有投影任务：queued/running 不重复创建；succeeded 须 {@link #isProjectionFactsComplete}。
     */
    public boolean isHealthyProjection(Long workspaceId, AsyncTask task) {
        if (!"succeeded".equals(task.getStatus())) {
            return true;
        }
        return projectionRepository.findByAsyncTaskIdAndWorkspaceId(task.getId(), workspaceId)
                .map(projection -> isProjectionFactsComplete(workspaceId, projection.getId()))
                .orElse(false);
    }

    /** 投影事实完整 = 同一 projection 下既有 data_cell 又有 L2 标注行。 */
    public boolean isProjectionFactsComplete(Long workspaceId, Long projectionId) {
        boolean hasCells = !dataCellRepository
                .findByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId)
                .isEmpty();
        long annotationCount = annotationRepository
                .countByWorkspaceProjectionIdAndWorkspaceId(projectionId, workspaceId);
        return hasCells && annotationCount > 0;
    }

    /** 删除与一次投影命令绑定的任务、投影记录与来源文件关联，解除 idempotency 阻塞。 */
    public void removeProjectionChain(Long workspaceId, AsyncTask task) {
        projectionRepository.findByAsyncTaskIdAndWorkspaceId(task.getId(), workspaceId)
                .ifPresent(projection -> {
                    projectionFileRepository
                            .findByWorkspaceProjectionIdAndWorkspaceId(projection.getId(), workspaceId)
                            .forEach(projectionFileRepository::delete);
                    projectionRepository.delete(projection);
                });
        taskRepository.delete(task);
    }

    /** 清除工作区全部投影事实，保留 feedback_event 与 import_file。 */
    public void wipeAnalysisFacts(Long workspaceId) {
        projectionFactWiper.wipeWorkspaceAnalysisFacts(workspaceId, null);
    }
}
