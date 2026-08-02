package com.insightflow.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 按配置与单次请求 {@link KnowledgeRetrievalOptions} 选择 RRF 或 Cross-encoder 精排。
 */
@Component
public class KnowledgeRerankerSelector {

    private final RrfOnlyKnowledgeReranker rrfOnly;
    private final Optional<CrossEncoderKnowledgeReranker> crossEncoder;
    private final Optional<KnowledgeRerankerProperties> properties;

    public KnowledgeRerankerSelector(
            RrfOnlyKnowledgeReranker rrfOnly,
            @Autowired(required = false) CrossEncoderKnowledgeReranker crossEncoder,
            @Autowired(required = false) KnowledgeRerankerProperties properties) {
        this.rrfOnly = rrfOnly;
        this.crossEncoder = Optional.ofNullable(crossEncoder);
        this.properties = Optional.ofNullable(properties);
    }

    public KnowledgeRerankOutcome rerank(
            String question,
            List<KnowledgeVectorStore.SearchCandidate> candidates,
            int finalLimit,
            KnowledgeRetrievalOptions options) {
        if (shouldUseCrossEncoder(options)) {
            return crossEncoder.orElseThrow().rerank(question, candidates, finalLimit);
        }
        return rrfOnly.rerank(question, candidates, finalLimit);
    }

    /** 解析写入评测/AgentRun 的检索版本标签。 */
    public String resolveRetrievalVersionLabel(KnowledgeRetrievalOptions options) {
        if (shouldUseCrossEncoder(options) && properties.isPresent()) {
            return properties.get().versionLabel();
        }
        return RrfOnlyKnowledgeReranker.RERANKER_VERSION;
    }

    private boolean shouldUseCrossEncoder(KnowledgeRetrievalOptions options) {
        if (crossEncoder.isEmpty()) {
            return false;
        }
        if (options != null && options.rerankerEnabled()) {
            return true;
        }
        return properties.map(KnowledgeRerankerProperties::enabled).orElse(false);
    }
}
