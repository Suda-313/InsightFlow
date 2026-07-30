package com.insightflow.evaluation.rag;

/**
 * 检索漏斗批次聚合指标，仅 retrieval-only 或带诊断的端到端批次填充。
 */
public record RagGoldRetrievalFunnelAggregate(
        double candidateDocumentRecallAt10,
        double candidateDocumentRecallAt30,
        double candidateDocumentRecallAt50,
        double candidateChunkRecallAt10,
        double candidateChunkRecallAt30,
        double candidateChunkRecallAt50,
        double crossDocumentDualDocumentHitRate,
        int candidateSourceLexicalOnly,
        int candidateSourceVectorOnly,
        int candidateSourceBoth,
        String evaluationMode) {
}
