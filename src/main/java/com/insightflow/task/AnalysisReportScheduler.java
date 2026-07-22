package com.insightflow.task;

import com.insightflow.entity.AsyncTask;
import com.insightflow.repository.AsyncTaskRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分析报告的持久调度入口。
 *
 * <p>报告创建后通过此调度器定时扫描 queued 或过期租约的 analysis_report 任务，
 * 领取租约并调用 {@link AnalysisReportTaskRunner} 执行。与投影调度器共享同一
 * async_task 表的租约机制，但通过 taskType 隔离领取范围。</p>
 */
@Component
public class AnalysisReportScheduler {

    /** 任务仓储按 analysis_report 类型领取最早的可恢复任务。 */
    private final AsyncTaskRepository taskRepository;

    /** Worker 在独立线程池内调用 ReportAgent 生成报告。 */
    private final AnalysisReportTaskRunner taskRunner;

    /** 每轮领取上限防止积压报告任务占满单体资源。 */
    private final int maxDispatchPerCycle;

    /** 应用实例级执行标识，租约过期后其它实例可安全接管。 */
    private final String workerId = "report-generator-" + UUID.randomUUID();

    /** 构造报告调度器。 */
    public AnalysisReportScheduler(
            AsyncTaskRepository taskRepository,
            AnalysisReportTaskRunner taskRunner,
            @Value("${insightflow.report.max-dispatch-per-cycle:2}") int maxDispatchPerCycle) {
        this.taskRepository = taskRepository;
        this.taskRunner = taskRunner;
        this.maxDispatchPerCycle = maxDispatchPerCycle;
    }

    /**
     * 固定延迟扫描 queued 或过期租约的 analysis_report 任务。
     * 与投影调度器使用相同的 dispatch-delay-ms 值，避免独立配置。
     */
    @Scheduled(fixedDelayString = "${insightflow.report.dispatch-delay-ms:5000}")
    public void scheduledDispatch() {
        dispatchClaimableTasks();
    }

    /**
     * 新命令提交后也可调用此方法触发即时唤醒，减少等待时间。
     * 没有可领取任务时立即结束本轮。
     */
    @Transactional
    public void dispatchClaimableTasks() {
        for (int dispatched = 0; dispatched < maxDispatchPerCycle; dispatched++) {
            OffsetDateTime now = OffsetDateTime.now();
            AsyncTask task = taskRepository.findNextClaimableTaskByType("analysis_report", now).orElse(null);
            if (task == null) {
                return;
            }
            if (!task.canBeClaimedAt(now)) {
                continue;
            }
            if (!task.hasAttemptsRemaining()) {
                task.markFailed("REPORT_RETRY_EXHAUSTED", "报告任务重试次数已耗尽。");
                continue;
            }
            task.claim(workerId, now.plusSeconds(120));
            taskRunner.run(task.getPublicId(), workerId);
        }
    }
}