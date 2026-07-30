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
        boolean rerankerEnabled) {

    public static RagGoldEvaluationRunRequest endToEnd() {
        return endToEnd(false);
    }

    public static RagGoldEvaluationRunRequest endToEnd(boolean rerankerEnabled) {
        return new RagGoldEvaluationRunRequest(
                RagGoldEvaluationRunMode.END_TO_END, Set.of(), null, false, rerankerEnabled);
    }

    public static RagGoldEvaluationRunRequest retrievalOnly(Set<String> caseKeysFilter, Path embeddingCacheDir) {
        return retrievalOnly(caseKeysFilter, embeddingCacheDir, false);
    }

    public static RagGoldEvaluationRunRequest retrievalOnly(
            Set<String> caseKeysFilter, Path embeddingCacheDir, boolean rerankerEnabled) {
        return new RagGoldEvaluationRunRequest(
                RagGoldEvaluationRunMode.RETRIEVAL_ONLY,
                caseKeysFilter == null ? Set.of() : Set.copyOf(caseKeysFilter),
                embeddingCacheDir,
                embeddingCacheDir != null,
                rerankerEnabled);
    }

    public boolean limitsCases() {
        return !caseKeysFilter.isEmpty();
    }

    public RagGoldEvaluationRunRequest {
        caseKeysFilter = caseKeysFilter == null ? Set.of() : Set.copyOf(caseKeysFilter);
    }
}
