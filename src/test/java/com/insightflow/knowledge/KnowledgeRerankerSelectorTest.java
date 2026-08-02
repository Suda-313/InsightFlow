package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeRerankerSelectorTest {

    @Test
    void usesRetrievalOptionsForEvaluationBatch() {
        KnowledgeRerankerProperties properties = new KnowledgeRerankerProperties();
        properties.setEnabled(false);
        CrossEncoderKnowledgeReranker crossEncoder = new CrossEncoderKnowledgeReranker(
                (query, documents, topN) -> List.of(),
                new RrfOnlyKnowledgeReranker(),
                30,
                "qwen3-rerank");
        KnowledgeRerankerSelector selector = new KnowledgeRerankerSelector(
                new RrfOnlyKnowledgeReranker(), crossEncoder, properties);

        assertThat(selector.resolveRetrievalVersionLabel(KnowledgeRetrievalOptions.withReranker(true)))
                .isEqualTo("knowledge:rrf:v3+rerank:qwen3-rerank:in30:rrf0:div0");
        assertThat(selector.resolveRetrievalVersionLabel(KnowledgeRetrievalOptions.withReranker(false)))
                .isEqualTo("knowledge:rrf:v3");
        assertThat(selector.resolveRetrievalVersionLabel(null)).isEqualTo("knowledge:rrf:v3");
    }

    @Test
    void retrievalVersionIncludesOfflineSelectionParameters() {
        KnowledgeRerankerProperties properties = new KnowledgeRerankerProperties();
        properties.setCandidateLimit(50);
        properties.setRrfWeight(0.25);
        properties.setDiversityPenalty(0.1);

        assertThat(properties.versionLabel())
                .isEqualTo("knowledge:rrf:v3+rerank:qwen3-rerank:in50:rrf0.25:div0.1");
    }
}
