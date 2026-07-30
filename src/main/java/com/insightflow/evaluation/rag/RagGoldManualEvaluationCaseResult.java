package com.insightflow.evaluation.rag;

/**
 * 人工金标 RAG 评测的脱敏逐题结果。
 *
 * <p>FROZEN split 只保留 {@code caseKey}、{@code status}、{@code failureStage}、{@code errorCode}；
 * 非 frozen 可附带计数型指标与 carry-forward 用的 hit@K/MRR/nDCG/耗时，但不记录断言原文、模型回答或 chunk 内容。</p>
 */
public record RagGoldManualEvaluationCaseResult(
        String caseKey,
        String status,
        String failureStage,
        String errorCode,
        String questionType,
        Integer expectedEvidenceCount,
        Integer retrievedExpectedEvidenceCount,
        Integer citedEvidenceCount,
        Integer correctCitationCount,
        Boolean ungrounded,
        Double requiredFactCoverageRate,
        Double forbiddenClaimHitRate,
        Boolean refusalCompliant,
        /** carry-forward：文档级 Recall@K；历史 JSON 缺字段时为 null。 */
        Boolean documentHitAt1,
        Boolean documentHitAt3,
        Boolean documentHitAt8,
        /** carry-forward：chunk 级 Recall@K。 */
        Boolean chunkHitAt1,
        Boolean chunkHitAt3,
        Boolean chunkHitAt8,
        /** carry-forward：MRR 单题贡献（reciprocal rank）。 */
        Double reciprocalRank,
        /** carry-forward：nDCG@8 单题值。 */
        Double ndcgAt8,
        /** 检索阶段耗时；carry-forward 或历史批次缺字段时为 null，聚合分位时跳过。 */
        Long retrievalLatencyMs,
        Long generationLatencyMs,
        Long totalLatencyMs,
        /** 单题检索诊断；FROZEN 与历史批次缺字段时为 null。 */
        RagGoldRetrievalCaseDiagnostics retrievalDiagnostics) {

    /** FROZEN split 最小脱敏视图：仅 case_key、状态与错误码。 */
    public static RagGoldManualEvaluationCaseResult frozenRedacted(
            String caseKey, String status, String failureStage, String errorCode) {
        return new RagGoldManualEvaluationCaseResult(
                caseKey, status, failureStage, errorCode,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
