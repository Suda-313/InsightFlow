package com.insightflow.task;

import com.insightflow.repository.AsyncTaskRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 独立于业务 Worker 的统一租约心跳。
 *
 * <p>长时间的导入、投影、调查和报告不能依赖业务线程自行续租：线程阻塞在 IO 或模型调用时，
 * 仍需由独立调度线程维持租约。续租失败或超过运行时限后，Guard 会在 Worker 的安全检查点
 * 阻止继续写入，旧执行实例不得覆盖已被重新领取任务的结果。</p>
 */
@Component
public class TaskLeaseHeartbeat {
    /** 心跳失败只记录任务标识，不记录任务 payload、业务数据或模型输出。 */
    private static final Logger log = LoggerFactory.getLogger(TaskLeaseHeartbeat.class);
    /** 原子续租端口以 owner 与执行版本同时限定更新行，防止旧实例续租。 */
    private final AsyncTaskRepository repository;
    /** 每次成功心跳延长的租约长度，与领取时使用同一通用配置。 */
    private final long leaseSeconds;
    /** 每次续租独立开启短事务，不能让一个任务的事务状态污染其它任务。 */
    private final TransactionTemplate transactionTemplate;
    /** 同一 public_id 同时只保留最新执行版本的 Guard，避免内存心跳重复续租。 */
    private final ConcurrentHashMap<UUID, Guard> guards = new ConcurrentHashMap<>();
    /** 进程退出时可关闭的守护线程；它不承担任何业务写入。 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "async-task-lease-heartbeat"); thread.setDaemon(true); return thread;
    });

    /**
     * 启动固定延迟心跳。固定延迟确保上一轮数据库续租尚未结束时不会并发叠加下一轮。
     */
    public TaskLeaseHeartbeat(AsyncTaskRepository repository,
            TransactionTemplate transactionTemplate,
            @Value("${insightflow.task.lease-seconds:120}") long leaseSeconds,
            @Value("${insightflow.task.heartbeat-seconds:30}") long heartbeatSeconds) {
        this.repository = repository; this.transactionTemplate = transactionTemplate; this.leaseSeconds = leaseSeconds;
        scheduler.scheduleWithFixedDelay(this::renewAll, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }
    /**
     * 注册当前 Worker 的运行边界，并以领取时的 executionVersion 作为不可变围栏。
     * 后续重新领取同一任务会替换此 Guard，使旧 Worker 无法注销或续租新执行。
     */
    public Guard register(UUID taskId, String workerId, int executionVersion, Duration maxRuntime) {
        Guard guard = new Guard(taskId, workerId, executionVersion, Instant.now(), maxRuntime);
        guards.put(taskId, guard); return guard;
    }
    /**
     * 旧执行可能在任务被重新领取后才结束；只有执行版本一致时才可注销，不能移除新 Worker 的心跳。
     */
    public void unregister(UUID taskId, int executionVersion) {
        guards.computeIfPresent(taskId, (ignored, guard) -> guard.executionVersion == executionVersion ? null : guard);
    }

    /**
     * 逐个任务尝试续租。单次数据库瞬断仅记录并在下一轮重试，不能终止其它运行任务的心跳。
     * 更新行数为零表示租约已失效或被接管，Guard 会在下一安全检查点抛出租约丢失异常。
     */
    void renewAll() {
        guards.values().forEach(guard -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    if (guard.active && !guard.isTimedOut()) {
                        guard.active = repository.renewLeaseIfOwned(
                                guard.taskId, guard.workerId, guard.executionVersion, leaseSeconds) == 1;
                    }
                });
            } catch (RuntimeException exception) {
                log.warn("Async task lease heartbeat failed for task {} and will retry", guard.taskId, exception);
            }
        });
    }
    /** 应用关闭时停止后台调度，避免 Spring 容器销毁后继续访问仓储。 */
    @PreDestroy void stop() { scheduler.shutdownNow(); }

    /**
     * Worker 持有的本次执行安全令牌；不直接写数据库，只在业务写入前判断是否仍可继续。
     */
    public static final class Guard {
        /** 仅用于和续租 SQL 绑定的对外任务标识，不泄露内部主键。 */
        private final UUID taskId;
        /** 当前进程实例的随机 owner，必须与领取时和数据库中的 owner 完全相同。 */
        private final String workerId;
        /** 每次 claim 递增的不可变版本，解决同一进程重试时 owner 相同却执行已过期的问题。 */
        private final int executionVersion;
        /** 记录单调墙钟起点以执行业务级最长运行时限，避免心跳无限续租失控任务。 */
        private final Instant startedAt;
        /** 任务类型对应的最大运行预算；它是租约续期之外的业务熔断边界。 */
        private final Duration maxRuntime;
        /** 续租 SQL 未命中时置为 false；volatile 让 Worker 线程能读取心跳线程的结果。 */
        private volatile boolean active = true;
        /** Guard 仅在 register 创建，避免外部伪造未登记的租约检查令牌。 */
        Guard(UUID taskId, String workerId, int executionVersion, Instant startedAt, Duration maxRuntime) { this.taskId=taskId;this.workerId=workerId;this.executionVersion=executionVersion;this.startedAt=startedAt;this.maxRuntime=maxRuntime; }
        /**
         * 写入业务事实或收敛终态前调用：超时优先收敛为受控失败，丢失租约则让旧 Worker 静默退出。
         */
        public void ensureActive() { if (isTimedOut()) throw new TaskExecutionTimeoutException(); if (!active) throw new TaskLeaseLostException(); }
        /** 到达上限即视为超时，避免边界时刻继续创建新的业务事实。 */
        boolean isTimedOut() { return !Instant.now().isBefore(startedAt.plus(maxRuntime)); }
    }
    /** 表示当前 owner 或执行版本已不再匹配；调用方不得再写入终态。 */
    public static class TaskLeaseLostException extends RuntimeException { }
    /** 表示本执行超过允许运行时长；调用方仅能尝试结束同一执行版本。 */
    public static class TaskExecutionTimeoutException extends RuntimeException { }
}
