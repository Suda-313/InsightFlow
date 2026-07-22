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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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

    private final AsyncTaskRepository taskRepository;
    private final AnalysisReportRepository reportRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ReportAgent reportAgent;
    private final DashboardService dashboardService;
    private final AnalysisReportCompletionService completionService;
    private final ObjectMapper objectMapper;

    public AnalysisReportTaskRunner(
            AsyncTaskRepository taskRepository,
            AnalysisReportRepository reportRepository,
            WorkspaceRepository workspaceRepository,
            ReportAgent reportAgent,
            DashboardService dashboardService,
            AnalysisReportCompletionService completionService,
            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.workspaceRepository = workspaceRepository;
        this.reportAgent = reportAgent;
        this.dashboardService = dashboardService;
        this.completionService = completionService;
        this.objectMapper = objectMapper;
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

            MergedData mergedData = buildMergedData(workspace.getPublicId());
            ReportResult result = reportAgent.generate(mergedData);
            String reportJson = objectMapper.writeValueAsString(result);

            completionService.complete(taskPublicId, workerId, reportJson);
        } catch (Exception exception) {
            completionService.fail(taskPublicId, workerId, "REPORT_GENERATION_FAILED", "报告生成失败，请稍后重试。");
        }
    }

    private MergedData buildMergedData(UUID workspacePublicId) {
        DashboardService.DashboardResponse dashboard = dashboardService.getDashboard(workspacePublicId);
        int totalTickets = dashboard.coverage().totalEvents();
        Map<String, Integer> issueMentions = new LinkedHashMap<>();
        for (DashboardService.IssueSummary issue : dashboard.topIssues()) {
            issueMentions.put(issue.canonicalKey(), issue.feedbackCount());
        }
        String summary = "看板数据聚合摘要";
        return new MergedData(summary, totalTickets, issueMentions);
    }
}
