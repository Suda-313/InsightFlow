package com.insightflow.controller;

import com.insightflow.service.DashboardService;
import com.insightflow.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 看板 API 的 HTTP 边界。
 *
 * <p>所有端点共享 {@code /api/v1/workspaces/{workspaceId}/dashboard} 前缀；
 * 注入 {@link WorkspaceService} 仅用于校验工作区存在，业务聚合由 {@link DashboardService} 完成。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/dashboard")
public class DashboardController {

    private final WorkspaceService workspaceService;
    private final DashboardService dashboardService;

    /**
     * 通过构造器注入依赖，保持 API 层可测试。
     */
    public DashboardController(WorkspaceService workspaceService, DashboardService dashboardService) {
        this.workspaceService = workspaceService;
        this.dashboardService = dashboardService;
    }

    /**
     * 返回看板首页聚合数据。
     */
    @GetMapping
    public DashboardService.DashboardResponse getDashboard(@PathVariable UUID workspaceId) {
        workspaceService.get(workspaceId);
        return dashboardService.getDashboard(workspaceId);
    }

    /**
     * 返回工作区下所有 issue 列表。
     */
    @GetMapping("/issues")
    public List<DashboardService.IssueSummary> getIssues(@PathVariable UUID workspaceId) {
        workspaceService.get(workspaceId);
        return dashboardService.getIssues(workspaceId);
    }

    /**
     * 返回单个 issue 的详细趋势与告警历史。
     */
    @GetMapping("/issues/{canonicalKey}")
    public DashboardService.IssueDetailResponse getIssueDetail(
            @PathVariable UUID workspaceId,
            @PathVariable String canonicalKey) {
        workspaceService.get(workspaceId);
        return dashboardService.getIssueDetail(workspaceId, canonicalKey);
    }
}
