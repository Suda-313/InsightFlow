package com.insightflow.evaluation.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagEvaluationRun;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.RagEvaluationRunRepository;
import com.insightflow.service.WorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 从 JSON 摘要或数据库加载上一轮人工金标评测结果，供 {@code --retry-failed-from} 合并重跑。
 */
@Component
public class RagGoldManualEvaluationPreviousRunLoader {

    private final WorkspaceService workspaceService;
    private final RagEvaluationRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public RagGoldManualEvaluationPreviousRunLoader(
            WorkspaceService workspaceService,
            RagEvaluationRunRepository runRepository,
            ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    /** 按批次 public_id 从数据库加载；Workspace 隔离校验不可省略。 */
    public RagGoldManualEvaluationPreviousRun loadFromRunId(UUID workspacePublicId, UUID runPublicId)
            throws Exception {
        Workspace workspace = workspaceService.get(workspacePublicId);
        RagEvaluationRun run = runRepository
                .findByPublicIdAndWorkspaceId(runPublicId, workspace.getId())
                .orElseThrow(() -> new IllegalArgumentException("上一轮批次不存在: " + runPublicId));
        List<RagGoldManualEvaluationCaseResult> caseResults = objectMapper.readValue(
                run.getCaseResultsJson(), new TypeReference<>() {});
        return new RagGoldManualEvaluationPreviousRun(runPublicId, run.getDatasetVersion(), caseResults);
    }

    /** 从 CLI 输出的 JSON 摘要加载；路径通常为 {@code output/rag-gold-runs/rag-gold-run-*.json}。 */
    public RagGoldManualEvaluationPreviousRun loadFromSummaryFile(Path summaryFile) throws Exception {
        if (!Files.exists(summaryFile)) {
            throw new IllegalArgumentException("摘要文件不存在: " + summaryFile);
        }
        Map<String, Object> summary = objectMapper.readValue(summaryFile.toFile(), new TypeReference<>() {});
        Object runPublicIdValue = summary.get("runPublicId");
        if (runPublicIdValue == null) {
            throw new IllegalArgumentException("摘要缺少 runPublicId: " + summaryFile);
        }
        UUID runPublicId = UUID.fromString(runPublicIdValue.toString());
        String datasetVersion = summary.get("datasetVersion") == null
                ? null
                : summary.get("datasetVersion").toString();
        List<RagGoldManualEvaluationCaseResult> caseResults = objectMapper.convertValue(
                summary.get("caseResults"), new TypeReference<>() {});
        return new RagGoldManualEvaluationPreviousRun(runPublicId, datasetVersion, caseResults);
    }

    /** 提取需要重跑的 case_key；无失败题时返回空列表。 */
    public List<String> failedCaseKeys(RagGoldManualEvaluationPreviousRun previousRun) {
        return previousRun.caseResults().stream()
                .filter(result -> !"succeeded".equals(result.status()))
                .map(RagGoldManualEvaluationCaseResult::caseKey)
                .toList();
    }

    /** 上一轮逐题结果按 case_key 索引，供 carry-forward 合并。 */
    public Map<String, RagGoldManualEvaluationCaseResult> indexByCaseKey(
            RagGoldManualEvaluationPreviousRun previousRun) {
        Map<String, RagGoldManualEvaluationCaseResult> indexed = new LinkedHashMap<>();
        for (RagGoldManualEvaluationCaseResult result : previousRun.caseResults()) {
            indexed.put(result.caseKey(), result);
        }
        return indexed;
    }

    /** 上一轮完整批次快照。 */
    public record RagGoldManualEvaluationPreviousRun(
            UUID runPublicId,
            String datasetVersionLabel,
            List<RagGoldManualEvaluationCaseResult> caseResults) {}
}
