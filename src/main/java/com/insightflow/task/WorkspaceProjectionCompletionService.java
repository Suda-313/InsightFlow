package com.insightflow.task;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import com.insightflow.repository.ProjectionFileRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.insightflow.agent.event.ProjectionCompletedEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 在独立短事务中收敛投影终态。
 *
 * <p>本阶段成功含义仅为“来源文件已可靠进入后续看板计算入口”；不代表主题、趋势或 Alert 已经计算。
 * 这些事实会在下一阶段以同一投影状态机扩展，而不是由报告任务补写。</p>
 */
@Service
public class WorkspaceProjectionCompletionService {

    /** 通用任务仓储用于再次确认租约 owner，旧 Worker 不得覆盖新 Worker 的终态。 */
    private final AsyncTaskRepository taskRepository;

    /** 投影记录仓储保存用户可追溯的状态与失败摘要。 */
    private final WorkspaceProjectionRepository projectionRepository;

    /** 来源关联仓储定位应被推进到 projected 的文件。 */
    private final ProjectionFileRepository projectionFileRepository;

    /** 文件仓储以 Workspace 条件限制最终状态更新。 */
    private final ImportFileRepository importFileRepository;

    /** Spring 事件发布器用于触发投影完成事件。 */
    private final ApplicationEventPublisher applicationEventPublisher;

    /** 构造投影完成服务。 */
    public WorkspaceProjectionCompletionService(
            AsyncTaskRepository taskRepository,
            WorkspaceProjectionRepository projectionRepository,
            ProjectionFileRepository projectionFileRepository,
            ImportFileRepository importFileRepository,
            ApplicationEventPublisher applicationEventPublisher) {
        this.taskRepository = taskRepository;
        this.projectionRepository = projectionRepository;
        this.projectionFileRepository = projectionFileRepository;
        this.importFileRepository = importFileRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 成功关闭状态投影任务；后续有真实指标计算后，此处仍是唯一允许提交 projected 的收口点。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId) {
        complete(taskPublicId, workerId, -1);
    }

    /** 完成操作以领取时记录的执行版本为围栏，旧投影器不能结束新执行。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId, int executionVersion) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !ownsLease(task, workerId, executionVersion) || !"projection".equals(task.getTaskType())) {
            return;
        }
        WorkspaceProjection projection = projectionRepository
                .findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                .orElse(null);
        if (projection == null) {
            task.markFailed("PROJECTION_RECORD_NOT_FOUND", "投影状态记录不存在。");
            return;
        }
        projectionFileRepository.findByWorkspaceProjectionIdAndWorkspaceId(projection.getId(), task.getWorkspaceId())
                .forEach(link -> importFileRepository.findByIdAndWorkspaceId(link.getImportFileId(), task.getWorkspaceId())
                        .ifPresent(ImportFile::markProjected));
        task.markSucceeded("{\"projection\":\"state_only\"}");
        projection.markSucceeded(projection.getSourceWindowStart(), projection.getSourceWindowEnd(), OffsetDateTime.now());
        applicationEventPublisher.publishEvent(
                new ProjectionCompletedEvent(this, String.valueOf(projection.getId()), String.valueOf(projection.getWorkspaceId())));
    }

    /**
     * 失败只影响投影状态；CSV 已经安全导入的事实保持 processed，用户可等待租约重试或人工排障。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId, String code, String message) {
        fail(taskPublicId, workerId, -1, code, message);
    }

    /** 已失效的投影器不能将后续重新领取的执行标记为失败。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId, int executionVersion, String code, String message) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !ownsLease(task, workerId, executionVersion) || !"projection".equals(task.getTaskType())) {
            return;
        }
        task.markFailed(code, message);
        projectionRepository.findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                .ifPresent(projection -> {
                    projection.markFailed(code, message);
                    projectionFileRepository.findByWorkspaceProjectionIdAndWorkspaceId(projection.getId(), task.getWorkspaceId())
                            .forEach(link -> importFileRepository
                                    .findByIdAndWorkspaceId(link.getImportFileId(), task.getWorkspaceId())
                                    .ifPresent(ImportFile::markProjectionFailed));
                });
    }

    private boolean ownsLease(AsyncTask task, String workerId, int executionVersion) {
        return executionVersion < 0 ? task.isLeaseOwnedBy(workerId) : task.isLeaseOwnedBy(workerId, executionVersion);
    }
}
