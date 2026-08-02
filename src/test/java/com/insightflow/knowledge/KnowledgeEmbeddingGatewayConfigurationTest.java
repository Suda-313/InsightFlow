package com.insightflow.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that local, no-key startup keeps the knowledge module constructible.
 *
 * <p>The fallback must exist when Agent is disabled, so document upload and review remain
 * available while publishing correctly reports that embeddings require model configuration.</p>
 */
class KnowledgeEmbeddingGatewayConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    DashScopeKnowledgeEmbeddingGateway.class,
                    UnavailableKnowledgeEmbeddingGateway.class)
            .withPropertyValues("insightflow.agent.enabled=false", "spring.ai.openai.api-key=");

    @Test
    void registersFallbackEmbeddingGatewayWhenAgentIsDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KnowledgeEmbeddingGateway.class);
            assertThat(context).hasSingleBean(UnavailableKnowledgeEmbeddingGateway.class);
        });
    }
}
