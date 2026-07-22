package com.insightflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insightflow.entity.Workspace;
import com.insightflow.service.DashboardService;
import com.insightflow.service.WorkspaceService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 看板 API 的 HTTP 边界测试；依赖服务全部 mock，只验证路由与响应契约。
 */
@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private DashboardService dashboardService;

    @Test
    void getDashboardReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        when(workspaceService.get(workspaceId)).thenReturn(new Workspace("test"));
        when(dashboardService.getDashboard(workspaceId)).thenReturn(new DashboardService.DashboardResponse(
                new DashboardService.DataCoverage(OffsetDateTime.now(), OffsetDateTime.now(), 10),
                List.of(new DashboardService.IssueSummary(UUID.randomUUID(), "login_failure", "登录失败", 5)),
                List.of(new DashboardService.AlertSummary(UUID.randomUUID(), 7, OffsetDateTime.now())),
                new DashboardService.BaselineStatus(1, 2),
                new DashboardService.ProjectionSummary(UUID.randomUUID(), "succeeded", OffsetDateTime.now())));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/dashboard", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.totalEvents").value(10))
                .andExpect(jsonPath("$.topIssues[0].canonicalKey").value("login_failure"))
                .andExpect(jsonPath("$.baselineStatus.active").value(2));
    }

    @Test
    void getIssuesReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        when(workspaceService.get(workspaceId)).thenReturn(new Workspace("test"));
        when(dashboardService.getIssues(workspaceId)).thenReturn(List.of(
                new DashboardService.IssueSummary(UUID.randomUUID(), "checkout_error", "结账失败", 12)));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/dashboard/issues", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canonicalKey").value("checkout_error"));
    }

    @Test
    void getIssueDetailReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        when(workspaceService.get(workspaceId)).thenReturn(new Workspace("test"));
        when(dashboardService.getIssueDetail(any(), any())).thenReturn(new DashboardService.IssueDetailResponse(
                UUID.randomUUID(),
                "login_failure",
                "登录失败",
                "active",
                List.of(new DashboardService.TrendPoint(OffsetDateTime.now(), 5)),
                List.of(),
                new DashboardService.BaselineInfo("active", 2.0, 0.5)));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/dashboard/issues/{canonicalKey}",
                        workspaceId, "login_failure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalKey").value("login_failure"))
                .andExpect(jsonPath("$.baseline.status").value("active"));
    }
}
