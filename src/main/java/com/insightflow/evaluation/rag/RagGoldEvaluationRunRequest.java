package com.insightflow.evaluation.rag;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 金标评测运行请求：模式、子集过滤与 embedding 缓存目录。
 */
public record RagGoldEvaluationRunRequest(
        RagGoldEvaluationRunMode mode,
        Set<String> caseKeysFilter,
        Path embeddingCacheDir,
        boolean useEmbeddingCache,
        boolean rerankerEnabled,
        boolean identifierSupplementEnabled,
        boolean subQueryQuotaEnabled,
        /** 后验证据门控；false 时检索行为与门控引入前一致。 */
        boolean evidenceGateEnabled) {

    public static RagGoldEvaluationRunRequest endToEnd() {
        return endToEnd(false);
    }

    public static RagGoldEvaluationRunRequest endToEnd(boolean rerankerEnabled) {
        return new RagGoldEvaluationRunRequest(
                RagGoldEvaluationRunMode.END_TO_END, Set.of(), null, false, rerankerEnabled, true, true, true);
    }

    public static RagGoldEvaluationRunRequest retrievalOnly(Set<String> caseKeysFilter, Path embeddingCacheDir) {
        return retrievalOnly(caseKeysFilter, embeddingCacheDir, false);
    }

    public static RagGoldEvaluationRunRequest retrievalOnly(
            Set<String> caseKeysFilter, Path embeddingCacheDir, boolean rerankerEnabled) {
        return retrievalOnly(caseKeysFilter, embeddingCacheDir, rerankerEnabled, true, true, true);
    }

    public static RagGoldEvaluationRunRequest retrievalOnly(
            Set<String> caseKeysFilter,
            Path embeddingCacheDir,
            boolean rerankerEnabled,
            boolean identifierSupplementEnabled,
            boolean subQueryQuotaEnabled) {
        return retrievalOnly(
                caseKeysFilter,
                embeddingCacheDir,
                rerankerEnabled,
                identifierSupplementEnabled,
                subQueryQuotaEnabled,
                true);
    }

    public static RagGoldEvaluationRunRequest retrievalOnly(
            Set<String> caseKeysFilter,
            Path embeddingCacheDir,
            boolean rerankerEnabled,
            boolean identifierSupplementEnabled,
            boolean subQueryQuotaEnabled,
            boolean evidenceGateEnabled) {
        return new RagGoldEvaluationRunRequest(
                RagGoldEvaluationRunMode.RETRIEVAL_ONLY,
                caseKeysFilter == null ? Set.of() : Set.copyOf(caseKeysFilter),
                embeddingCacheDir,
                embeddingCacheDir != null,
                rerankerEnabled,
                identifierSupplementEnabled,
                subQueryQuotaEnabled,
                evidenceGateEnabled);
    }

    public boolean limitsCases() {
        return !caseKeysFilter.isEmpty();
    }

    public RagGoldEvaluationRunRequest {
        caseKeysFilter = caseKeysFilter == null ? Set.of() : Set.copyOf(caseKeysFilter);
    }
}
