package com.insightflow.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.agent.analyzer.ClassificationAnalyzer;
import com.insightflow.agent.analyzer.RiskAnalyzer;
import com.insightflow.agent.analyzer.SentimentAnalyzer;
import com.insightflow.agent.dto.CellInsight;
import com.insightflow.agent.dto.ClassificationResult;
import com.insightflow.agent.dto.RiskResult;
import com.insightflow.agent.dto.SentimentResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * CellAnalysisAgent 单元测试。
 */
class CellAnalysisAgentTest {

    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final ChatClient chatClient = mock(ChatClient.class);

    private final ClassificationAnalyzer classificationAnalyzer = new ClassificationAnalyzer(chatClient);
    private final SentimentAnalyzer sentimentAnalyzer = new SentimentAnalyzer(chatClient);
    private final RiskAnalyzer riskAnalyzer = new RiskAnalyzer(chatClient);

    private final CellAnalysisAgent cellAnalysisAgent = new CellAnalysisAgent(
            orchestrator, classificationAnalyzer, sentimentAnalyzer, riskAnalyzer);

    @Test
    void analyzeCallsThreeAnalyzersInParallelAndMergesResult() {
        ClassificationResult classification = new ClassificationResult(
                "login_failure", 0.9, "用户无法登录", List.of("登录"));
        SentimentResult sentiment = new SentimentResult(
                "negative", "high", List.of("愤怒"));
        RiskResult risk = new RiskResult("low", false, List.of());

        when(orchestrator.parallel(anyList(), eq("cell text")))
                .thenReturn(List.of(classification, sentiment, risk));

        CellInsight result = cellAnalysisAgent.analyze("cell text");

        assertThat(result.classification()).isEqualTo(classification);
        assertThat(result.sentiment()).isEqualTo(sentiment);
        assertThat(result.risk()).isEqualTo(risk);
        assertThat(result.summary()).isEqualTo("用户无法登录\n---\n");
        assertThat(result.keywords()).containsExactly("登录", "愤怒");

        verify(orchestrator).parallel(anyList(), eq("cell text"));
    }
}
