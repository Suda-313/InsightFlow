package com.insightflow.knowledge;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认精排：保持 RRF 分数顺序，仅截断到 finalLimit。
 *
 * <p>作为生产默认与 Cross-encoder 失败时的确定性回退，保证精排链路可关可退。</p>
 */
@Component
public class RrfOnlyKnowledgeReranker implements KnowledgeReranker {

    static final String RERANKER_NAME = "rrf-only";
    static final String RERANKER_VERSION = "knowledge:rrf:v3";

    @Override
    public KnowledgeRerankOutcome rerank(
            String question, List<KnowledgeVectorStore.SearchCandidate> candidates, int finalLimit) {
        int limit = Math.max(0, Math.min(finalLimit, candidates.size()));
        return new KnowledgeRerankOutcome(
                candidates.subList(0, limit),
                RERANKER_NAME,
                RERANKER_VERSION,
                0L,
                false,
                candidates.size());
    }
}
