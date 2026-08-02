package com.insightflow.controller;

import com.insightflow.service.DashboardService;
import com.insightflow.service.WorkspaceService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 看板 API 的 HTTP 边界。
 *
 * <p>所有端点共享 {@code /api/v1/workspaces/{workspaceId}/dashboard} 前缀；
 * 注入 {@link WorkspaceService} 仅用于校验工作区存在，业务聚合由 {@link DashboardService} 完成。
 * 可选 {@code from}/{@code to}（ISO 日期）统一 Dashboard 与数据分析页的分析窗口。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class DashboardController {

    private final WorkspaceService workspaceService;
    private final DashboardService dashboardService;

    public DashboardController(WorkspaceService workspaceService, DashboardService dashboardService) {
        this.workspaceService = workspaceService;
        this.dashboardService = dashboardService;
    }

    /** 看板首页聚合数据；未传日期时默认数据截止日往前 7 天。 */
    @GetMapping("/dashboard")
    public DashboardService.DashboardResponse getDashboard(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        workspaceService.get(workspaceId);
        return dashboardService.getDashboard(workspaceId, from, to);
    }

    /** 工作区下所有 issue 列表；计数按分析窗口内桶汇总。 */
    @GetMapping("/issues")
    public List<DashboardService.IssueSummary> getIssues(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        workspaceService.get(workspaceId);
        return dashboardService.getIssues(workspaceId, from, to);
    }

    /** 单个 issue 的详细趋势与告警历史；趋势与样本按分析窗口过滤。 */
    @GetMapping("/issues/{canonicalKey}")
    public DashboardService.IssueDetailResponse getIssueDetail(
            @PathVariable UUID workspaceId,
            @PathVariable String canonicalKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        workspaceService.get(workspaceId);
        return dashboardService.getIssueDetail(workspaceId, canonicalKey, from, to);
    }

    /** L2→L1 钻取：某表达类目（如 expr_suggestion）下的 Pack 内议题分布。 */
    @GetMapping("/expressions/{expressionKey}/topics")
    public DashboardService.ExpressionTopicsResponse getExpressionTopics(
            @PathVariable UUID workspaceId,
            @PathVariable String expressionKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        workspaceService.get(workspaceId);
        return dashboardService.getExpressionTopics(workspaceId, expressionKey, from, to);
    }

    /** alert_eligible 子集概览：Pack 内可行动议题的计数、趋势与最近告警（只读副屏）。 */
    @GetMapping("/dashboard/alert-eligible")
    public DashboardService.AlertEligibleOverviewResponse getAlertEligibleOverview(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        workspaceService.get(workspaceId);
        return dashboardService.getAlertEligibleOverview(workspaceId, from, to);
    }

    /** L2×L1 交叉样本：某表达类目下命中指定议题的脱敏原文，最多 5 条。 */
    @GetMapping("/expressions/{expressionKey}/topics/{topicKey}/samples")
    public List<DashboardService.FeedbackSample> getExpressionTopicSamples(
            @PathVariable UUID workspaceId,
            @PathVariable String expressionKey,
            @PathVariable String topicKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        workspaceService.get(workspaceId);
        return dashboardService.getExpressionTopicSamples(workspaceId, expressionKey, topicKey, from, to);
    }
}
