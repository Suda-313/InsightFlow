package com.insightflow.task;

import com.insightflow.entity.AsyncTask;
import com.insightflow.repository.AsyncTaskRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告任务短事务领取服务，与 WorkspaceProjectionLeaseService 模式一致。
 */
@Service
public class AnalysisReportLeaseService {

    private final AsyncTaskRepository taskRepository;
    private final long leaseSeconds;

    public AnalysisReportLeaseService(AsyncTaskRepository taskRepository,
                                      @Value("${insightflow.projection.lease-seconds:120}") long leaseSeconds) {
        this.taskRepository = taskRepository;
        this.leaseSeconds = leaseSeconds;
    }

    @Transactional
    public Optional<UUID> claimNext(String workerId) {
        OffsetDateTime now = OffsetDateTime.now();
        AsyncTask task = taskRepository.findNextClaimableTaskByType("analysis_report", now).orElse(null);
        if (task == null) return Optional.empty();
        if (!task.canBeClaimedAt(now)) return Optional.empty();
        if (!task.hasAttemptsRemaining()) {
            task.markFailed("REPORT_RETRY_EXHAUSTED", "报告重试次数已耗尽");
            return Optional.empty();
        }
        task.claim(workerId, now.plusSeconds(leaseSeconds));
        return Optional.of(task.getPublicId());
    }
}