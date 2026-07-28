package com.insightflow.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.analyzer.ClassificationAnalyzer;
import com.insightflow.agent.analyzer.RiskAnalyzer;
import com.insightflow.agent.analyzer.SentimentAnalyzer;
import com.insightflow.prompt.OperationalPromptCatalog;
import com.insightflow.service.AgentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class CellAnalysisAgentTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OperationalPromptCatalog promptCatalog = new OperationalPromptCatalog();
    private final AgentRunService agentRunService = mock(AgentRunService.class);
    private final ClassificationAnalyzer classificationAnalyzer = new ClassificationAnalyzer(
            chatClient, objectMapper, promptCatalog, agentRunService, "qwen-test");
    private final SentimentAnalyzer sentimentAnalyzer = new SentimentAnalyzer(
            chatClient, objectMapper, promptCatalog, agentRunService, "qwen-test");
    private final RiskAnalyzer riskAnalyzer = new RiskAnalyzer(
            chatClient, objectMapper, promptCatalog, agentRunService, "qwen-test");

    @Test
    void createsAgentSuccessfully() {
        CellAnalysisAgent agent = new CellAnalysisAgent(
                mock(AgentOrchestrator.class),
                classificationAnalyzer, sentimentAnalyzer, riskAnalyzer);
        assertThat(agent).isNotNull();
    }
}
