package com.insightflow.investigation;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 调查任务持久化调度入口。
 *
 * <p>调度器只领取任务并交给独立线程池，业务取证留给 Worker；这样调度循环短且可恢复，不会因单次 Tool 查询阻塞后续待办。</p>
 */
@Component
public class InvestigationScheduler {

    /** 租约服务负责短事务领取，调度器本身不直接修改任务状态。 */
    private final InvestigationTaskLeaseService leaseService;

    /** Worker 只读取证并冻结快照，不拥有任何处置写权限。 */
    private final InvestigationTaskRunner taskRunner;

    /** 单轮领取上限限制在小值，避免告警风暴挤占导入与报告线程。 */
    private final int maxDispatchPerCycle;

    /** 每个进程实例持有独立 Worker 标识，完成时用于租约归属校验。 */
    private final String workerId = "investigation-worker-" + UUID.randomUUID();

    /** 构造器将调度节流与执行职责分离，配置不影响取证规则。 */
    public InvestigationScheduler(
            InvestigationTaskLeaseService leaseService,
            InvestigationTaskRunner taskRunner,
            @Value("${insightflow.investigation.max-dispatch-per-cycle:2}") int maxDispatchPerCycle) {
        this.leaseService = leaseService;
        this.taskRunner = taskRunner;
        this.maxDispatchPerCycle = maxDispatchPerCycle;
    }

    /**
     * 周期性恢复 queued 或租约已过期的调查任务；没有可领任务时立即结束本轮。
     */
    @Scheduled(fixedDelayString = "${insightflow.investigation.dispatch-delay-ms:5000}")
    public void scheduledDispatch() {
        for (int index = 0; index < maxDispatchPerCycle; index++) {
            if (leaseService.claimNext(workerId).map(claimed -> {
                taskRunner.run(claimed.taskId(), claimed.workerId(), claimed.executionVersion());
                return true;
            }).isEmpty()) {
                return;
            }
        }
    }
}
