package com.insightflow.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.analyzer.ClassificationAnalyzer;
import com.insightflow.agent.analyzer.RiskAnalyzer;
import com.insightflow.agent.analyzer.SentimentAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class CellAnalysisAgentTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClassificationAnalyzer classificationAnalyzer = new ClassificationAnalyzer(chatClient, objectMapper);
    private final SentimentAnalyzer sentimentAnalyzer = new SentimentAnalyzer(chatClient, objectMapper);
    private final RiskAnalyzer riskAnalyzer = new RiskAnalyzer(chatClient, objectMapper);

    @Test
    void createsAgentSuccessfully() {
        CellAnalysisAgent agent = new CellAnalysisAgent(
                mock(AgentOrchestrator.class),
                classificationAnalyzer, sentimentAnalyzer, riskAnalyzer);
        assertThat(agent).isNotNull();
    }
}
