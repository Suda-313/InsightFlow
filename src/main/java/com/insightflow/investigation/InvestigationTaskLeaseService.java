package com.insightflow.investigation;

import com.insightflow.entity.AsyncTask;
import com.insightflow.repository.AsyncTaskRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调查任务的短事务租约领取服务。
 *
 * <p>复用 async_task 的 SKIP LOCKED 查询与租约字段，不引入消息队列。Worker 只有持有此处写入的 owner 才能完成任务，进程崩溃后过期租约可被后续调度恢复。</p>
 */
@Service
public class InvestigationTaskLeaseService {

    /** 所有调查任务都从统一持久化任务表领取。 */
    private final AsyncTaskRepository asyncTaskRepository;

    /** 租约时长独立配置，防止长调查占用导入或报告任务的恢复节奏。 */
    private final long leaseSeconds;

    /** 构造器显式绑定任务仓储和调查专用租约期限。 */
    public InvestigationTaskLeaseService(
            AsyncTaskRepository asyncTaskRepository,
            @Value("${insightflow.investigation.lease-seconds:180}") long leaseSeconds) {
        this.asyncTaskRepository = asyncTaskRepository;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * 原子领取最早的可恢复调查任务；达到重试上限时记录受控失败而不继续无限重试。
     */
    @Transactional
    public Optional<UUID> claimNext(String workerId) {
        OffsetDateTime now = OffsetDateTime.now();
        AsyncTask task = asyncTaskRepository.findNextClaimableTaskByType("investigation", now).orElse(null);
        if (task == null || !task.canBeClaimedAt(now)) {
            return Optional.empty();
        }
        if (!task.hasAttemptsRemaining()) {
            task.markFailed("INVESTIGATION_RETRY_EXHAUSTED", "调查任务重试次数已耗尽");
            return Optional.empty();
        }
        task.claim(workerId, now.plusSeconds(leaseSeconds));
        return Optional.of(task.getPublicId());
    }
}
