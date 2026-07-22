package com.insightflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insightflow.entity.AnalysisReport;
import com.insightflow.entity.AsyncTask;
import com.insightflow.service.ReportCommandService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 分析报告 API 的 HTTP 边界测试；依赖服务全部 mock，只验证路由与响应契约。
 */
@WebMvcTest(ReportController.class)
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReportCommandService reportCommandService;

    @Test
    void createReportReturnsAccepted() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        AsyncTask task = AsyncTask.queuedReport(1L, "idem-key", "{}");
        when(reportCommandService.createReport(any(), any(), any(), any())).thenReturn(task);

        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/analysis-reports", workspaceId)
                        .header("Idempotency-Key", "idem-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileIds\":[\"" + UUID.randomUUID() + "\"],\"time_range\":{\"start\":\"2024-01-01T00:00:00Z\",\"end\":\"2024-01-07T00:00:00Z\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(task.getPublicId().toString()))
                .andExpect(jsonPath("$.type").value("analysis_report"))
                .andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    void getReportReturnsOk() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        UUID reportPublicId = UUID.randomUUID();
        AnalysisReport report = AnalysisReport.queued(1L, 2L, "v1", OffsetDateTime.now(), "{}");
        report.markSucceeded("{\"summary\":\"test\"}");
        when(reportCommandService.findReport(workspaceId, reportPublicId)).thenReturn(report);

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/analysis-reports/{reportId}", workspaceId, reportPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.report.summary").value("test"));
    }
}
