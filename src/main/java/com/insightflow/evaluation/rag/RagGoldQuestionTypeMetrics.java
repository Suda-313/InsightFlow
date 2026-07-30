package com.insightflow.evaluation.rag;

/**
 * 按 {@code question_type} 分项的 RAG 金标指标摘要。
 *
 * <p>只保留聚合数值，不包含单题断言或证据正文。</p>
 */
public record RagGoldQuestionTypeMetrics(
        int caseCount,
        double documentRecallAt8,
        double chunkRecallAt8,
        /** 该题型中全部 requirement 组均在 Top8 满足的题占比。 */
        double finalEvidenceCoverageAt8,
        /** 该题型的主检索指标：SINGLE 等为 chunk R@8，CROSS/VERSION 为 requirement 组覆盖。 */
        double primaryRecallAt8,
        /** 主指标名称：chunk_recall_at8 或 requirement_group_coverage_at8。 */
        String primaryMetricName,
        /** CROSS 题型的最终 Top8 双文档命中率；其他题型为 0。 */
        double finalCrossDocumentDualHitAt8,
        /** RRF Top8 未命中、精排 Top8 命中的题数。 */
        int rerankGainedCaseCount,
        /** RRF Top8 命中、精排 Top8 未命中的题数。 */
        int rerankLostCaseCount,
        /** 精排后相关证据名次下降或跌出 Top8 的题数。 */
        int rerankDemotedCaseCount,
        double requiredFactCoverageRate,
        double forbiddenClaimHitRate,
        Double shouldRefuseComplianceRate) {
}
