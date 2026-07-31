package com.insightflow.evaluation.rag;

import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import java.util.List;

/**
 * retrieval-only 单题执行上下文：数据集 checksum、embedding 缓存与 CROSS 分解输入。
 */
public record RagGoldRetrievalExecutionContext(
        String datasetChecksum,
        String embeddingModel,
        RagGoldEvaluationEmbeddingCache embeddingCache,
        boolean useEmbeddingCache,
        boolean rerankerEnabled,
        boolean identifierSupplementEnabled,
        boolean subQueryQuotaEnabled,
        boolean evidenceGateEnabled,
        RagGoldQuestionType questionType,
        List<RagGoldEvidenceSnapshot> evidences) {

    public RagGoldRetrievalExecutionContext(
            String datasetChecksum,
            String embeddingModel,
            RagGoldEvaluationEmbeddingCache embeddingCache,
            boolean useEmbeddingCache) {
        this(datasetChecksum, embeddingModel, embeddingCache, useEmbeddingCache, false, true, true, true, null, List.of());
    }

    public RagGoldRetrievalExecutionContext(
            String datasetChecksum,
            String embeddingModel,
            RagGoldEvaluationEmbeddingCache embeddingCache,
            boolean useEmbeddingCache,
            boolean rerankerEnabled) {
        this(
                datasetChecksum,
                embeddingModel,
                embeddingCache,
                useEmbeddingCache,
                rerankerEnabled,
                true,
                true,
                true,
                null,
                List.of());
    }

    public RagGoldRetrievalExecutionContext {
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
    }
}
