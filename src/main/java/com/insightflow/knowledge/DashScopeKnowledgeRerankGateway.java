package com.insightflow.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.insightflow.config.AgentApiKeyPresentCondition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * DashScope OpenAI-compatible {@code /v1/reranks} 精排网关。
 *
 * <p>与聊天/embedding 共用 base-url 与 API Key，但使用更短读超时，避免精排拖死检索线程。</p>
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class DashScopeKnowledgeRerankGateway implements KnowledgeRerankGateway {

    private final RestClient restClient;
    private final String model;
    private final String instruct;

    public DashScopeKnowledgeRerankGateway(
            @Qualifier("knowledgeRerankRestClient") RestClient restClient,
            @Value("${insightflow.knowledge.reranker.model:qwen3-rerank}") String model,
            @Value("${insightflow.knowledge.reranker.instruct:Given a web search query, retrieve relevant passages that answer the query.}") String instruct) {
        this.restClient = restClient;
        this.model = model;
        this.instruct = instruct;
    }

    @Override
    public List<RerankScore> rerank(String query, List<String> documents, int topN) {
        if (documents.isEmpty()) {
            return List.of();
        }
        RerankRequest request = new RerankRequest(model, query, documents, topN, instruct);
        RerankResponse response = restClient.post()
                .uri("/v1/reranks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(RerankResponse.class);
        if (response == null || response.results == null || response.results.isEmpty()) {
            throw new IllegalStateException("精排 API 返回空结果");
        }
        List<RerankScore> scores = new ArrayList<>(response.results.size());
        for (RerankResultItem item : response.results) {
            scores.add(new RerankScore(item.index, item.relevanceScore));
        }
        scores.sort(Comparator.comparingDouble(RerankScore::relevanceScore).reversed());
        return scores;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankRequest(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN,
            String instruct) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RerankResponse {
        @JsonProperty("results")
        private List<RerankResultItem> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RerankResultItem {
        @JsonProperty("index")
        private int index;

        @JsonProperty("relevance_score")
        private double relevanceScore;
    }

    /** 将 RestClient 异常包装为运行时异常，供 CrossEncoder 回退 RRF。 */
    public static RuntimeException asRerankFailure(RestClientException exception) {
        return new IllegalStateException("精排 API 调用失败", exception);
    }
}
