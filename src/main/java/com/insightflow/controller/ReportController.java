package com.insightflow.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.AnalysisReport;
import com.insightflow.entity.AsyncTask;
import com.insightflow.service.ReportCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 分析报告的 HTTP 边界。
 *
 * <p>路径中的 Workspace UUID 始终传入服务层做归属校验；接口不返回原始 CSV、对象键或内部主键。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/analysis-reports")
public class ReportController {

    private final ReportCommandService reportCommandService;
    private final ObjectMapper objectMapper;

    public ReportController(ReportCommandService reportCommandService, ObjectMapper objectMapper) {
        this.reportCommandService = reportCommandService;
        this.objectMapper = objectMapper;
    }

    /**
     * 受理异步分析报告命令；同一 Workspace、命令类型和幂等键只会创建一个任务。
     */
    @PostMapping
    public ResponseEntity<TaskResponse> create(
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateReportRequest request) {
        ReportCommandService.TimeRange serviceTimeRange = new ReportCommandService.TimeRange(
                request.timeRange().start(), request.timeRange().end());
        AsyncTask task = reportCommandService.createReport(
                workspaceId, request.fileIds(), serviceTimeRange, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{reportId}")
                .buildAndExpand(task.getPublicId())
                .toUri();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(TaskResponse.from(task));
    }

    /**
     * 读取单个分析报告的状态和结构化内容。
     */
    @GetMapping("/{reportId}")
    public ReportResponse get(
            @PathVariable UUID workspaceId,
            @PathVariable UUID reportId) {
        AnalysisReport report = reportCommandService.findReport(workspaceId, reportId);
        return ReportResponse.from(report);
    }

    /**
     * 下载报告为 Markdown 文件。
     */
    @GetMapping("/{reportId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID workspaceId,
            @PathVariable UUID reportId) {
        AnalysisReport report = reportCommandService.findReport(workspaceId, reportId);
        if (!"succeeded".equals(report.getStatus())) {
            throw new IllegalArgumentException("报告尚未生成完成，请稍后再试。");
        }
        try {
            Map<String, Object> json = objectMapper.readValue(report.getReportJson(), Map.class);
            String reportContent = (String) json.getOrDefault("report", "无报告内容");
            byte[] content = reportContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename("analysis-report.md", java.nio.charset.StandardCharsets.UTF_8)
                    .build());
            headers.setContentLength(content.length);
            return ResponseEntity.ok().headers(headers).body(content);
        } catch (Exception e) {
            throw new RuntimeException("报告解析失败", e);
        }
    }

    /**
     * 列出工作区下所有报告，按创建时间倒序。
     */
    @GetMapping
    public List<ReportResponse> list(@PathVariable UUID workspaceId) {
        return reportCommandService.listReports(workspaceId).stream()
                .map(ReportResponse::from)
                .toList();
    }

    /**
     * 创建报告请求的最小契约。
     */
    public record CreateReportRequest(
            List<UUID> fileIds,
            @NotNull @JsonProperty("time_range") TimeRange timeRange) {

        public record TimeRange(@JsonProperty("start") OffsetDateTime start,
                                @JsonProperty("end") OffsetDateTime end) {
        }
    }

    /**
     * 异步命令的 202 响应。
     */
    public record TaskResponse(
            UUID id,
            String type,
            String status,
            @JsonProperty("created_at") OffsetDateTime createdAt) {

        static TaskResponse from(AsyncTask task) {
            return new TaskResponse(task.getPublicId(), task.getTaskType(), task.getStatus(), task.getCreatedAt());
        }
    }

    /**
     * 分析报告详情响应。
     */
    public record ReportResponse(
            UUID id,
            String status,
            @JsonRawValue String report,
            String errorCode,
            String errorMessage,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {

        static ReportResponse from(AnalysisReport report) {
            return new ReportResponse(
                    report.getPublicId(),
                    report.getStatus(),
                    report.getReportJson(),
                    report.getErrorCode(),
                    report.getErrorMessage(),
                    report.getCreatedAt(),
                    report.getUpdatedAt());
        }
    }
}
