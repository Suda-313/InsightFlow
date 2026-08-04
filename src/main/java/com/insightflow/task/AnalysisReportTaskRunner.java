package com.insightflow.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.report.MergedData;
import com.insightflow.agent.report.ReportAgent;
import com.insightflow.agent.report.ReportResult;
import com.insightflow.entity.AnalysisReport;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AnalysisReportRepository;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.WorkspaceRepository;
import com.insightflow.service.DashboardService;
import com.insightflow.report.OperationalReportEvidenceAssembler;
import com.insightflow.report.OperationalReportRiskAssembler;
import com.insightflow.report.ReportTimeRange;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 分析报告 Worker 单体内执行入口。
 *
 * <p>Worker 先校验租约，再聚合看板数据，调用 {@link ReportAgent#generate} 生成报告，
 * 最后通过完成服务收敛终态。</p>
 */
@Component
public class AnalysisReportTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisReportTaskRunner.class);

    private final AsyncTaskRepository taskRepository;
    private final AnalysisReportRepository reportRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ReportAgent reportAgent;
    private final DashboardService dashboardService;
    private final AnalysisReportCompletionService completionService;
    private final ObjectMapper objectMapper;
    /** 只装配已确认调查的冻结证据，报告任务不重跑 Agent 或 Tool。 */
    private final OperationalReportEvidenceAssembler reportEvidenceAssembler;
    /** 报告风险来自告警产生时冻结的 P0-P3 快照，而非模型推测或当前队列重算。 */
    private final OperationalReportRiskAssembler reportRiskAssembler;

    public AnalysisReportTaskRunner(
            AsyncTaskRepository taskRepository,
            AnalysisReportRepository reportRepository,
            WorkspaceRepository workspaceRepository,
            ReportAgent reportAgent,
            DashboardService dashboardService,
            AnalysisReportCompletionService completionService,
            ObjectMapper objectMapper,
            OperationalReportEvidenceAssembler reportEvidenceAssembler,
            OperationalReportRiskAssembler reportRiskAssembler) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.workspaceRepository = workspaceRepository;
        this.reportAgent = reportAgent;
        this.dashboardService = dashboardService;
        this.completionService = completionService;
        this.objectMapper = objectMapper;
        this.reportEvidenceAssembler = reportEvidenceAssembler;
        this.reportRiskAssembler = reportRiskAssembler;
    }

    /**
     * 在线程池执行报告生成；重复调度或租约已转移时安全返回。
     */
    @Async("analysisReportTaskExecutor")
    public void run(UUID taskPublicId, String workerId) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !"analysis_report".equals(task.getTaskType()) || !task.isLeaseOwnedBy(workerId)) {
            return;
        }
        try {
            AnalysisReport report = reportRepository
                    .findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                    .orElse(null);
            if (report == null) {
                completionService.fail(taskPublicId, workerId, "REPORT_RECORD_NOT_FOUND", "报告记录不存在。");
                return;
            }
            report.markRunning();
            reportRepository.saveAndFlush(report);

            Workspace workspace = workspaceRepository.findById(task.getWorkspaceId()).orElse(null);
            if (workspace == null) {
                completionService.fail(taskPublicId, workerId, "WORKSPACE_NOT_FOUND", "工作区不存在。");
                return;
            }

            ReportTimeRange timeRange = ReportTimeRange.fromScopeJson(objectMapper, report.getScopeJson());
            var risks = reportRiskAssembler.forTimeRange(workspace.getPublicId(), timeRange.start(), timeRange.end());
            MergedData mergedData = buildMergedData(workspace.getPublicId(), timeRange, risks);
            var evidence = reportEvidenceAssembler.forTimeRange(
                    workspace.getPublicId(), timeRange.start(), timeRange.end(), report.getOperationalScope());
            String evidenceJson = objectMapper.writeValueAsString(evidence);
            String reportContent = scopeBoundaryNote(report.getOperationalScope())
                    + reportAgent.generate(workspace.getPublicId(), mergedData);
            String reportJson = objectMapper.writeValueAsString(
                    Map.of("report", reportContent, "generatedAt", OffsetDateTime.now().toString(),
                            "timeRange", timeRange, "risks", risks, "evidence", evidence));

            completionService.complete(taskPublicId, workerId, reportJson, evidenceJson);
        } catch (Exception exception) {
            log.error("报告生成失败: {}", exception.getMessage(), exception);
            completionService.fail(taskPublicId, workerId, "REPORT_GENERATION_FAILED", "报告生成失败，请稍后重试。");
        }
    }

    private MergedData buildMergedData(
            UUID workspacePublicId,
            ReportTimeRange timeRange,
            java.util.List<OperationalReportRiskAssembler.ReportRisk> risks) {
        DashboardService.DashboardResponse dashboard = dashboardService.getDashboardForTimeRange(
                workspacePublicId, timeRange.start(), timeRange.end());
        int totalTickets = dashboard.coverage().totalEvents();
        Map<String, Integer> issueMentions = new LinkedHashMap<>();
        for (DashboardService.IssueSummary issue : dashboard.topIssues()) {
            issueMentions.put(issue.canonicalKey(), issue.feedbackCount());
        }
        Map<String, Integer> expressionMentions = new LinkedHashMap<>();
        for (DashboardService.ExpressionCount expression : dashboard.expressionSummary().distribution()) {
            expressionMentions.put(expression.key(), expression.feedbackCount());
        }
        String summary = "看板数据聚合摘要";
        return new MergedData(summary, totalTickets, issueMentions, expressionMentions, risks);
    }

    /**
     * 版本复盘尚未接入版本和活动事件源，必须在产物中显式说明边界，避免模型把时间相关性写成版本因果。
     */
    private String scopeBoundaryNote(com.insightflow.report.OperationalReportScope scope) {
        if (scope == com.insightflow.report.OperationalReportScope.VERSION_REVIEW) {
            return "## 版本复盘边界\n当前未接入版本或活动事件数据；下文仅引用已确认调查证据，不可据此推断版本因果。\n\n";
        }
        return "";
    }
}
