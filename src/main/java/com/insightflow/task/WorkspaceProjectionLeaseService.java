package com.insightflow.task;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.entity.ProjectionFile;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import com.insightflow.repository.ProjectionFileRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以短事务领取自动投影任务，并把来源文件推进到 projecting。
 *
 * <p>它与 CSV 导入领取服务分开，避免任务类型、失败状态和配置相互耦合；两者仍复用 async_task
 * 的 PostgreSQL 租约和 SKIP LOCKED 语义。</p>
 */
@Service
public class WorkspaceProjectionLeaseService {

    /** 任务仓储按 projection 类型领取最早的可恢复任务。 */
    private final AsyncTaskRepository taskRepository;

    /** 投影仓储校验任务与 Workspace 的一对一状态记录。 */
    private final WorkspaceProjectionRepository projectionRepository;

    /** 输入关联仓储提供需要推进状态的来源文件。 */
    private final ProjectionFileRepository projectionFileRepository;

    /** 文件仓储始终以 Workspace 二次过滤，禁止异步任务跨租户改写文件。 */
    private final ImportFileRepository importFileRepository;

    /** 单次投影租约应覆盖当前仅状态闭环的执行，并为后续计算留出合理窗口。 */
    private final long leaseSeconds;

    /** 构造投影短事务领取服务。 */
    public WorkspaceProjectionLeaseService(
            AsyncTaskRepository taskRepository,
            WorkspaceProjectionRepository projectionRepository,
            ProjectionFileRepository projectionFileRepository,
            ImportFileRepository importFileRepository,
            @Value("${insightflow.task.lease-seconds:120}") long leaseSeconds) {
        this.taskRepository = taskRepository;
        this.projectionRepository = projectionRepository;
        this.projectionFileRepository = projectionFileRepository;
        this.importFileRepository = importFileRepository;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * 领取一个 projection 任务；领取成功后 Worker 只接收公开任务 UUID 和租约 owner。
     */
    @Transactional
    public Optional<ClaimedTask> claimNext(String workerId) {
        OffsetDateTime now = OffsetDateTime.now();
        AsyncTask task = taskRepository.findNextClaimableTaskByType("projection", now).orElse(null);
        if (task == null || !task.canBeClaimedAt(now)) {
            return Optional.empty();
        }
        WorkspaceProjection projection = projectionRepository
                .findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                .orElse(null);
        if (projection == null) {
            task.markFailed("PROJECTION_RECORD_NOT_FOUND", "投影状态记录不存在。");
            return Optional.empty();
        }
        if (!task.hasAttemptsRemaining()) {
            task.markFailed("PROJECTION_RETRY_EXHAUSTED", "投影任务重试次数已耗尽。");
            projection.markFailed("PROJECTION_RETRY_EXHAUSTED", "投影任务重试次数已耗尽。");
            updateSourceFiles(projection, task.getWorkspaceId(), ImportFile::markProjectionFailed);
            return Optional.empty();
        }
        task.claim(workerId, now.plusSeconds(leaseSeconds));
        projection.markRunning();
        updateSourceFiles(projection, task.getWorkspaceId(), ImportFile::markProjecting);
        return Optional.of(new ClaimedTask(task.getPublicId(), workerId, task.getAttemptCount()));
    }

    /** 在同一短事务中更新所有冻结文件，确保页面不会显示任务 running 而文件仍为 pending。 */
    private void updateSourceFiles(
            WorkspaceProjection projection, Long workspaceId, java.util.function.Consumer<ImportFile> transition) {
        projectionFileRepository.findByWorkspaceProjectionIdAndWorkspaceId(projection.getId(), workspaceId)
                .forEach(link -> importFileRepository.findByIdAndWorkspaceId(link.getImportFileId(), workspaceId)
                        .ifPresent(transition));
    }

    /** 已领取任务传递给异步 Worker 的最小安全内容。 */
    public record ClaimedTask(UUID taskId, String workerId, int executionVersion) {
    }
}
