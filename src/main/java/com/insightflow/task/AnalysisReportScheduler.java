package com.insightflow.task;

import com.insightflow.config.AgentApiKeyPresentCondition;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 分析报告的持久调度入口。委托 AnalysisReportLeaseService 处理事务边界。
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class AnalysisReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisReportScheduler.class);

    private final AnalysisReportLeaseService leaseService;
    private final AnalysisReportTaskRunner taskRunner;
    private final int maxDispatchPerCycle;
    private final String workerId = "report-generator-" + UUID.randomUUID();

    public AnalysisReportScheduler(AnalysisReportLeaseService leaseService,
                                   AnalysisReportTaskRunner taskRunner,
                                   @Value("${insightflow.report.max-dispatch-per-cycle:2}") int maxDispatchPerCycle) {
        this.leaseService = leaseService;
        this.taskRunner = taskRunner;
        this.maxDispatchPerCycle = maxDispatchPerCycle;
    }

    @Scheduled(fixedDelayString = "${insightflow.report.dispatch-delay-ms:5000}")
    public void scheduledDispatch() {
        for (int i = 0; i < maxDispatchPerCycle; i++) {
            leaseService.claimNext(workerId).ifPresentOrElse(
                    taskId -> taskRunner.run(taskId, workerId),
                    () -> { return; });
        }
    }
}
