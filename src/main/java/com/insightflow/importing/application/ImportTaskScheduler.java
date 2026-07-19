package com.insightflow.importing.application;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 单体内的持久任务调度入口：应用启动和固定间隔扫描都会从数据库恢复未完成导入。
 */
@Component
public class ImportTaskScheduler {

    /** 领取服务负责数据库锁和租约，调度器本身不直接读写任务状态。 */
    private final ImportTaskLeaseService leaseService;

    /** Worker 在线程池解析 CSV，避免 Web 请求和调度线程被 IO 阻塞。 */
    private final ImportTaskRunner taskRunner;

    /** 每轮上限防止积压任务一次性占满应用的异步线程池。 */
    private final int maxDispatchPerCycle;

    /** 当前应用实例的执行标识；重启后会变化，过期租约可安全转移给新实例。 */
    private final String workerId = "importer-" + UUID.randomUUID();

    /** 构造调度器；不依赖 HTTP 请求，因此重启后的孤儿任务也会被发现。 */
    public ImportTaskScheduler(
            ImportTaskLeaseService leaseService,
            ImportTaskRunner taskRunner,
            @Value("${insightflow.import.max-dispatch-per-cycle}") int maxDispatchPerCycle) {
        this.leaseService = leaseService;
        this.taskRunner = taskRunner;
        this.maxDispatchPerCycle = maxDispatchPerCycle;
    }

    /**
     * 固定延迟扫描持久化任务；从上轮结束再计时，避免慢 MinIO 影响时并发堆积多个扫描轮次。
     */
    @Scheduled(fixedDelayString = "${insightflow.import.dispatch-delay-ms}")
    public void scheduledDispatch() {
        dispatchClaimableTasks();
    }

    /**
     * 新任务事务提交后和定时器都可调用该方法；没有可领取任务时立即返回而不空转。
     */
    public void dispatchClaimableTasks() {
        for (int dispatched = 0; dispatched < maxDispatchPerCycle; dispatched++) {
            ImportTaskLeaseService.ClaimedTask claimed = leaseService.claimNext(workerId).orElse(null);
            if (claimed == null) {
                return;
            }
            taskRunner.run(claimed.taskId(), claimed.workerId());
        }
    }
}
