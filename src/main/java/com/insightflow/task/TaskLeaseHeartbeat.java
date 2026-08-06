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

/** 独立于业务 Worker 的统一租约心跳；续租失败后 Guard 会阻止旧 Worker 的后续安全检查点。 */
@Component
public class TaskLeaseHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(TaskLeaseHeartbeat.class);
    private final AsyncTaskRepository repository;
    private final long leaseSeconds;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<UUID, Guard> guards = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "async-task-lease-heartbeat"); thread.setDaemon(true); return thread;
    });

    public TaskLeaseHeartbeat(AsyncTaskRepository repository,
            TransactionTemplate transactionTemplate,
            @Value("${insightflow.task.lease-seconds:120}") long leaseSeconds,
            @Value("${insightflow.task.heartbeat-seconds:30}") long heartbeatSeconds) {
        this.repository = repository; this.transactionTemplate = transactionTemplate; this.leaseSeconds = leaseSeconds;
        scheduler.scheduleWithFixedDelay(this::renewAll, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }
    public Guard register(UUID taskId, String workerId, int executionVersion, Duration maxRuntime) {
        Guard guard = new Guard(taskId, workerId, executionVersion, Instant.now(), maxRuntime);
        guards.put(taskId, guard); return guard;
    }
    /** A previous execution may finish after a reclaim; it must not unregister the new heartbeat. */
    public void unregister(UUID taskId, int executionVersion) {
        guards.computeIfPresent(taskId, (ignored, guard) -> guard.executionVersion == executionVersion ? null : guard);
    }

    /** A transient database failure must not terminate future heartbeats for every running task. */
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
    @PreDestroy void stop() { scheduler.shutdownNow(); }
    public static final class Guard {
        private final UUID taskId; private final String workerId; private final int executionVersion; private final Instant startedAt; private final Duration maxRuntime; private volatile boolean active = true;
        Guard(UUID taskId, String workerId, int executionVersion, Instant startedAt, Duration maxRuntime) { this.taskId=taskId;this.workerId=workerId;this.executionVersion=executionVersion;this.startedAt=startedAt;this.maxRuntime=maxRuntime; }
        public void ensureActive() { if (isTimedOut()) throw new TaskExecutionTimeoutException(); if (!active) throw new TaskLeaseLostException(); }
        boolean isTimedOut() { return !Instant.now().isBefore(startedAt.plus(maxRuntime)); }
    }
    public static class TaskLeaseLostException extends RuntimeException { }
    public static class TaskExecutionTimeoutException extends RuntimeException { }
}
