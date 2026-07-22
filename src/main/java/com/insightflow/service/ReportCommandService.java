package com.insightflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.common.exception.ImportValidationException;
import com.insightflow.entity.AnalysisReport;
import com.insightflow.entity.AnalysisReportFile;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AnalysisReportFileRepository;
import com.insightflow.repository.AnalysisReportRepository;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.ImportFileRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分析报告的命令用例层，负责冻结输入并创建异步报告任务。
 *
 * <p>服务只受理命令和创建任务；实际看板数据聚合、LLM 生成由独立 Worker 完成。</p>
 */
@Service
public class ReportCommandService {

    private static final String REPORT_VERSION = "v1";

    private final AnalysisReportRepository reportRepository;
    private final AsyncTaskRepository taskRepository;
    private final WorkspaceService workspaceService;
    private final ImportFileRepository importFileRepository;
    private final AnalysisReportFileRepository reportFileRepository;
    private final ObjectMapper objectMapper;

    public ReportCommandService(
            AnalysisReportRepository reportRepository,
            AsyncTaskRepository taskRepository,
            WorkspaceService workspaceService,
            ImportFileRepository importFileRepository,
            AnalysisReportFileRepository reportFileRepository,
            ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.taskRepository = taskRepository;
        this.workspaceService = workspaceService;
        this.importFileRepository = importFileRepository;
        this.reportFileRepository = reportFileRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建幂只读分析报告命令；同一幂等键不会创建重复任务。
     */
    @Transactional
    public AsyncTask createReport(UUID workspacePublicId, List<UUID> fileIds, TimeRange timeRange, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ImportValidationException("Idempotency-Key 不能为空。");
        }
        if (fileIds == null || fileIds.isEmpty()) {
            if (timeRange == null || timeRange.start() == null || timeRange.end() == null) {
                throw new ImportValidationException("报告至少需要选择来源文件或指定时间范围。");
            }
        }
        if (timeRange == null || timeRange.start() == null || timeRange.end() == null) {
            throw new ImportValidationException("报告时间范围不能为空。");
        }

        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();

        AsyncTask existing = taskRepository
                .findByWorkspaceIdAndTaskTypeAndIdempotencyKey(workspaceId, "analysis_report", idempotencyKey.trim())
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        List<ImportFile> files = fileIds != null && !fileIds.isEmpty() ? resolveFiles(workspaceId, fileIds) : List.of();
        OffsetDateTime snapshotAt = OffsetDateTime.now();
        String scopeJson = writeScope(fileIds, timeRange, snapshotAt);

        AsyncTask task = taskRepository.saveAndFlush(
                AsyncTask.queuedReport(workspaceId, idempotencyKey.trim(), scopeJson));

        AnalysisReport report = reportRepository.saveAndFlush(
                AnalysisReport.queued(workspaceId, task.getId(), REPORT_VERSION, snapshotAt, scopeJson));

        for (ImportFile file : files) {
            reportFileRepository.saveAndFlush(AnalysisReportFile.of(report.getId(), workspaceId, file.getId()));
        }

        return task;
    }

    /**
     * 按公开 UUID 和 Workspace 读取报告实体。
     */
    public AnalysisReport findReport(UUID workspacePublicId, UUID reportPublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        return reportRepository.findByPublicIdAndWorkspaceId(reportPublicId, workspace.getId())
                .orElseThrow(() -> new ImportValidationException("报告不存在或不属于当前工作区。"));
    }

    private List<ImportFile> resolveFiles(Long workspaceId, List<UUID> fileIds) {
        List<ImportFile> files = new ArrayList<>(fileIds.size());
        for (UUID fileId : fileIds) {
            ImportFile file = importFileRepository.findByWorkspaceIdAndPublicId(workspaceId, fileId)
                    .orElseThrow(() -> new ImportValidationException("文件不存在或不属于当前工作区: " + fileId));
            files.add(file);
        }
        return files;
    }

    private String writeScope(List<UUID> fileIds, TimeRange timeRange, OffsetDateTime snapshotAt) {
        try {
            Map<String, Object> scope = new LinkedHashMap<>();
            if (fileIds != null && !fileIds.isEmpty()) {
                scope.put("fileIds", fileIds);
            }
            scope.put("timeRange", Map.of("start", timeRange.start(), "end", timeRange.end()));
            scope.put("snapshotAt", snapshotAt);
            return objectMapper.writeValueAsString(scope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize report scope", exception);
        }
    }

    /**
     * 报告请求的时间范围。
     */
    public record TimeRange(OffsetDateTime start, OffsetDateTime end) {
    }
}
