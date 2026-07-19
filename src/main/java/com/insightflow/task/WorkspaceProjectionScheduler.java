package com.insightflow.task;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动投影的持久调度入口。
 *
 * <p>提交后的即时唤醒只降低等待时间；固定扫描才是应用重启、线程异常或进程中断后的恢复保证。</p>
 */
@Component
public class WorkspaceProjectionScheduler {

    /** 租约服务负责 PostgreSQL 领取和文件/投影进入 running 的原子状态变更。 */
    private final WorkspaceProjectionLeaseService leaseService;

    /** Worker 在独立受限线程池内收敛投影状态。 */
    private final WorkspaceProjectionTaskRunner taskRunner;

    /** 每轮领取上限防止积压投影任务占满单体资源。 */
    private final int maxDispatchPerCycle;

    /** 应用实例级执行标识，租约过期后其它实例可安全接管。 */
    private final String workerId = "projector-" + UUID.randomUUID();

    /** 构造持久投影调度器。 */
    public WorkspaceProjectionScheduler(
            WorkspaceProjectionLeaseService leaseService,
            WorkspaceProjectionTaskRunner taskRunner,
            @Value("${insightflow.projection.max-dispatch-per-cycle}") int maxDispatchPerCycle) {
        this.leaseService = leaseService;
        this.taskRunner = taskRunner;
        this.maxDispatchPerCycle = maxDispatchPerCycle;
    }

    /** 固定延迟扫描 queued 或过期租约的 projection 任务。 */
    @Scheduled(fixedDelayString = "${insightflow.projection.dispatch-delay-ms}")
    public void scheduledDispatch() {
        dispatchClaimableTasks();
    }

    /** 新命令提交后和定时扫描均调用此方法；没有可领取任务时立即结束本轮。 */
    public void dispatchClaimableTasks() {
        for (int dispatched = 0; dispatched < maxDispatchPerCycle; dispatched++) {
            WorkspaceProjectionLeaseService.ClaimedTask claimed = leaseService.claimNext(workerId).orElse(null);
            if (claimed == null) {
                return;
            }
            taskRunner.run(claimed.taskId(), claimed.workerId());
        }
    }
}
