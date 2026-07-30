package com.insightflow.config;

import com.insightflow.knowledge.KnowledgeRerankerProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 精排专用 HTTP 客户端：短超时，与聊天/embedding 隔离。
 */
@Configuration
@Conditional(AgentApiKeyPresentCondition.class)
@EnableConfigurationProperties(KnowledgeRerankerProperties.class)
public class KnowledgeRerankConfiguration {

    @Bean
    RestClient knowledgeRerankRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            KnowledgeRerankerProperties rerankerProperties) {
        Duration timeout = Duration.ofSeconds(Math.max(1, rerankerProperties.timeoutSeconds()));
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return restClientBuilder
                .baseUrl(rerankerProperties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
    }
}
