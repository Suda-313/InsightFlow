package com.insightflow.task;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以数据库行为准领取待执行导入任务，并在进程中断后重新领取过期租约。
 *
 * <p>此服务的事务刻意保持很短：只做 `FOR UPDATE SKIP LOCKED`、状态转换和租约写入，绝不在
 * 持锁事务中读取 MinIO 或解析 CSV。</p>
 */
@Service
public class ImportTaskLeaseService {

    /** 任务仓储执行 PostgreSQL 行锁查询并保存领取结果。 */
    private final AsyncTaskRepository taskRepository;

    /** 文件仓储在重试耗尽时同步写入安全可见的文件失败状态。 */
    private final ImportFileRepository importFileRepository;

    /** 单次租约长度需覆盖正常 CSV 解析；超时任务仍可由后续扫描恢复。 */
    private final long leaseSeconds;

    /** 构造短事务领取服务，配置只影响租约时长而不改变任务最大重试次数。 */
    public ImportTaskLeaseService(
            AsyncTaskRepository taskRepository,
            ImportFileRepository importFileRepository,
            @Value("${insightflow.import.lease-seconds}") long leaseSeconds) {
        this.taskRepository = taskRepository;
        this.importFileRepository = importFileRepository;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * 领取最早的 queued 或租约过期任务；无任务时返回空，调度器立即停止本轮扫描。
     */
    @Transactional
    public Optional<ClaimedTask> claimNext(String workerId) {
        OffsetDateTime now = OffsetDateTime.now();
        AsyncTask task = taskRepository.findNextClaimableImportTask(now).orElse(null);
        if (task == null || !task.canBeClaimedAt(now)) {
            return Optional.empty();
        }
        if (!task.hasAttemptsRemaining()) {
            task.markFailed("IMPORT_RETRY_EXHAUSTED", "导入任务重试次数已耗尽。");
            importFileRepository.findByIdAndWorkspaceId(task.getImportFileId(), task.getWorkspaceId())
                    .ifPresent(ImportFile::markFailed);
            return Optional.empty();
        }
        task.claim(workerId, now.plusSeconds(leaseSeconds));
        return Optional.of(new ClaimedTask(task.getPublicId(), workerId));
    }

    /**
     * 已领取任务交给异步 Worker 时只传公开 UUID 与租约 owner，避免把内部键传出持久化边界。
     */
    public record ClaimedTask(UUID taskId, String workerId) {
    }
}
