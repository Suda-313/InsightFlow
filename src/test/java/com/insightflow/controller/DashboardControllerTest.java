package com.insightflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insightflow.entity.Workspace;
import com.insightflow.service.DashboardService;
import com.insightflow.service.WorkspaceService;
import com.insightflow.security.JwtAuthenticationFilter;
import com.insightflow.security.JwtTokenService;
import com.insightflow.security.WorkspaceAccessInterceptor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 看板 API 的 HTTP 边界测试；依赖服务全部 mock，只验证路由与响应契约。
 */
@WebMvcTest(DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private DashboardService dashboardService;

    /** MVC 切片不装配真实成员仓储，授权逻辑由 WorkspaceAccessService 的单元测试覆盖。 */
    @MockBean
    private WorkspaceAccessInterceptor workspaceAccessInterceptor;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void allowWorkspaceInterceptor() throws Exception {
        when(workspaceAccessInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void getDashboardReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(workspaceService.get(workspaceId)).thenReturn(new Workspace("test", 1L));
        when(dashboardService.getDashboard(eq(workspaceId), any(), any())).thenReturn(new DashboardService.DashboardResponse(
                new DashboardService.DataCoverage(now, now, 10),
                new DashboardService.WindowInfo(now.minusDays(7), now),
                List.of(new DashboardService.IssueSummary(UUID.randomUUID(), "login_failure", "登录失败", 5)),
                List.of(new DashboardService.AlertSummary(
                        UUID.randomUUID(), "login_failure", "login_failure", 7, now)),
                new DashboardService.BaselineStatus(1, 2),
                new DashboardService.ProjectionSummary(UUID.randomUUID(), "succeeded", now),
                new DashboardService.ExpressionSummary(List.of(), List.of(), 0, "game-chaoziran", "v1")));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/dashboard", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.totalEvents").value(10))
                .andExpect(jsonPath("$.analysisWindow.start").exists())
                .andExpect(jsonPath("$.topIssues[0].canonicalKey").value("login_failure"))
                .andExpect(jsonPath("$.baselineStatus.active").value(2));
    }

    @Test
    void getIssuesReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        when(workspaceService.get(workspaceId)).thenReturn(new Workspace("test", 1L));
        when(dashboardService.getIssues(eq(workspaceId), any(), any())).thenReturn(List.of(
                new DashboardService.IssueSummary(UUID.randomUUID(), "checkout_error", "结账失败", 12)));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/issues", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canonicalKey").value("checkout_error"));
    }

    @Test
    void getIssueDetailReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(workspaceService.get(workspaceId)).thenReturn(new Workspace("test", 1L));
        when(dashboardService.getIssueDetail(any(), any(), any(), any())).thenReturn(new DashboardService.IssueDetailResponse(
                UUID.randomUUID(),
                "login_failure",
                "登录失败",
                "active",
                List.of(new DashboardService.TrendPoint(now, 5)),
                List.of(),
                new DashboardService.BaselineInfo("active", 2.0, 0.5),
                List.of(),
                new DashboardService.WindowInfo(now.minusDays(7), now)));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/issues/{canonicalKey}",
                        workspaceId, "login_failure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalKey").value("login_failure"))
                .andExpect(jsonPath("$.baseline.status").value("active"));
    }

    @Test
    void getExpressionTopicsReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(workspaceService.get(workspaceId)).thenReturn(new Workspace("test", 1L));
        when(dashboardService.getExpressionTopics(eq(workspaceId), eq("expr_suggestion"), any(), any()))
                .thenReturn(new DashboardService.ExpressionTopicsResponse(
                        "expr_suggestion", "game-chaoziran", "v1",
                        List.of(new DashboardService.TopicCount(UUID.randomUUID(), "topic_matchmaking", "匹配/组队", 8)),
                        new DashboardService.WindowInfo(now.minusDays(7), now)));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/expressions/{expressionKey}/topics",
                        workspaceId, "expr_suggestion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expressionKey").value("expr_suggestion"))
                .andExpect(jsonPath("$.topicPackId").value("game-chaoziran"))
                .andExpect(jsonPath("$.topics[0].canonicalKey").value("topic_matchmaking"));
    }

    @Test
    void getExpressionTopicSamplesReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(workspaceService.get(workspaceId)).thenReturn(new Workspace("test", 1L));
        when(dashboardService.getExpressionTopicSamples(eq(workspaceId), eq("expr_suggestion"), eq("topic_matchmaking"), any(), any()))
                .thenReturn(List.of(new DashboardService.FeedbackSample("希望优化匹配速度", now, "app_store")));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/expressions/{expressionKey}/topics/{topicKey}/samples",
                        workspaceId, "expr_suggestion", "topic_matchmaking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("希望优化匹配速度"))
                .andExpect(jsonPath("$[0].sourceKind").value("app_store"));
    }

    @Test
    void getAlertEligibleOverviewReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(workspaceService.get(workspaceId)).thenReturn(new Workspace("test", 1L));
        when(dashboardService.getAlertEligibleOverview(eq(workspaceId), any(), any()))
                .thenReturn(new DashboardService.AlertEligibleOverviewResponse(
                        new DashboardService.WindowInfo(now.minusDays(7), now),
                        12,
                        5,
                        List.of(new DashboardService.AlertEligibleTopicSummary(
                                UUID.randomUUID(), "topic_stability", "稳定性/bug", 8, "up")),
                        List.of(new DashboardService.AlertEligibleTrendPoint(now.minusDays(1), 12)),
                        List.of(new DashboardService.AlertSummary(
                                UUID.randomUUID(), "稳定性/bug", "topic_stability", 8, now)),
                        "game-chaoziran",
                        "game-chaoziran:v2"));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/dashboard/alert-eligible", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFeedbackCount").value(12))
                .andExpect(jsonPath("$.eligibleTopicCount").value(5))
                .andExpect(jsonPath("$.topics[0].canonicalKey").value("topic_stability"))
                .andExpect(jsonPath("$.topics[0].trendDirection").value("up"))
                .andExpect(jsonPath("$.recentAlerts[0].issueKey").value("topic_stability"));
    }
}
