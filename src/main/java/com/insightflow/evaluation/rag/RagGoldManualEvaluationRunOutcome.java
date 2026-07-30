package com.insightflow.evaluation.rag;

import java.util.List;
import java.util.UUID;

/**
 * 一次人工金标 RAG 评测的完整运行结果，含持久化批次 ID 与脱敏逐题摘要。
 */
public record RagGoldManualEvaluationRunOutcome(
        UUID runPublicId,
        RagEvaluationRunResult runResult,
        List<RagGoldManualEvaluationCaseResult> manualCaseResults,
        boolean hasPartialFailures,
        boolean frozenSplit) {

    public RagGoldManualEvaluationRunOutcome {
        manualCaseResults = List.copyOf(manualCaseResults);
    }
}
