package com.insightflow.evaluation.rag;

import com.insightflow.entity.RagGoldQuestionType;

/**
 * 单题人工金标 RAG 评测的确定性得分。
 *
 * <p>只保留计数与布尔标记，供聚合器计算 Recall@K、MRR 与断言指标。</p>
 */
public record RagGoldManualCaseScore(
        String caseKey,
        RagGoldQuestionType questionType,
        boolean shouldRefuse,
        boolean hasExpectedEvidence,
        boolean documentHitAt1,
        boolean documentHitAt3,
        boolean documentHitAt8,
        boolean chunkHitAt1,
        boolean chunkHitAt3,
        boolean chunkHitAt8,
        double reciprocalRank,
        double ndcgAt8,
        int requiredFacts,
        int coveredFacts,
        int forbiddenClaims,
        int hitForbiddenClaims,
        double citationSupportRate,
        boolean refusalCompliant,
        RagEvaluationObservation observation,
        /** retrieval-only 或带诊断批次填充；端到端无候选列表时为 null。 */
        RagGoldCaseRetrievalFunnel retrievalFunnel,
        /** 单题检索诊断；含候选/精排/最终 Top8 公开 ID。 */
        RagGoldRetrievalCaseDiagnostics retrievalDiagnostics,
        /** 最终 Top8 是否满足全部 requirement 组。 */
        boolean requirementGroupCoverageAt8) {
}
