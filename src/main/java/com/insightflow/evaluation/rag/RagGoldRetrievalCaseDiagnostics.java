package com.insightflow.evaluation.rag;

import java.util.List;

/**
 * 单题检索诊断：区分候选未命中、精排降权、等价证据与多证据部分命中。
 *
 * <p>写入非 FROZEN 批次的 caseResults JSON；不含 chunk 正文或模型回答。</p>
 */
public record RagGoldRetrievalCaseDiagnostics(
        /** 金标 chunk 在 RRF 候选 Top50 中首次 relevant 的 rank（1-based），未命中为 0。 */
        int goldChunkRrfRank,
        /** chunk 级候选 Recall@10/30/50。 */
        boolean candidateHitAt10,
        boolean candidateHitAt30,
        boolean candidateHitAt50,
        /** 精排前（RRF 候选序）首个金标 chunk rank；精排后（最终 Top8）首个金标 chunk rank。 */
        int rerankBeforeRank,
        int rerankAfterRank,
        /** 精排器标识；未启用精排时为 RRF-only 版本标签。 */
        String rerankerName,
        Long rerankLatencyMs,
        boolean rerankFallbackUsed,
        int rerankInputCount,
        /** 最终 Top8 的 document/chunk 公开 UUID 列表（非内部 ID）。 */
        List<String> finalTop8DocumentIds,
        List<String> finalTop8ChunkIds,
        /** 最终 Top8 是否满足全部 evidence requirement 组（AND across groups）。 */
        boolean requirementGroupCoverageAt8,
        /** CROSS 题在最终 Top8 是否命中 ≥2 个 distinct 文档。 */
        boolean finalCrossDocumentDualHitAt8,
        /** 每个 requirement 组的 RRF/精排漏斗；无 evidence 时为空列表。 */
        List<RagGoldRequirementGroupDiagnostics> requirementGroups,
        /** CROSS 分解后的子查询文本；未分解时为单元素原问题或空。 */
        List<String> subQueries,
        /** 各子查询 RRF 候选数量，与 {@link #subQueries} 一一对应。 */
        List<Integer> candidatesPerSubQuery) {

    /** 向后兼容：无 requirement 组明细与分解字段。 */
    public RagGoldRetrievalCaseDiagnostics(
            int goldChunkRrfRank,
            boolean candidateHitAt10,
            boolean candidateHitAt30,
            boolean candidateHitAt50,
            int rerankBeforeRank,
            int rerankAfterRank,
            String rerankerName,
            Long rerankLatencyMs,
            boolean rerankFallbackUsed,
            int rerankInputCount,
            List<String> finalTop8DocumentIds,
            List<String> finalTop8ChunkIds,
            boolean requirementGroupCoverageAt8,
            boolean finalCrossDocumentDualHitAt8) {
        this(
                goldChunkRrfRank,
                candidateHitAt10,
                candidateHitAt30,
                candidateHitAt50,
                rerankBeforeRank,
                rerankAfterRank,
                rerankerName,
                rerankLatencyMs,
                rerankFallbackUsed,
                rerankInputCount,
                finalTop8DocumentIds,
                finalTop8ChunkIds,
                requirementGroupCoverageAt8,
                finalCrossDocumentDualHitAt8,
                List.of(),
                List.of(),
                List.of());
    }

    public RagGoldRetrievalCaseDiagnostics {
        finalTop8DocumentIds = List.copyOf(finalTop8DocumentIds);
        finalTop8ChunkIds = List.copyOf(finalTop8ChunkIds);
        requirementGroups = List.copyOf(requirementGroups);
        subQueries = List.copyOf(subQueries);
        candidatesPerSubQuery = List.copyOf(candidatesPerSubQuery);
    }
}
