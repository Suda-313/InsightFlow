package com.insightflow.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.prompt.ChatPromptTemplate;
import com.insightflow.prompt.LiteralChatModelCaller;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 金标运行器测试：验证运行器使用固定 fixture 和线上同一 Prompt 模板发起模型调用，并汇总规则评分。
 */
class GoldEvaluationRunnerTest {

    /**
     * 单题运行成功时，应保留 Prompt/数据集版本、模型用量和事实覆盖结果，供后续批次比较。
     */
    @Test
    void runsCasesWithFixedFixtureAndReturnsScoredMetrics() {
        GoldEvaluationDatasetLoader datasetLoader = mock(GoldEvaluationDatasetLoader.class);
        EvaluationFixtureLoader fixtureLoader = mock(EvaluationFixtureLoader.class);
        LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistant = mock(AssistantMessage.class);
        GoldEvaluationCase evaluationCase = new GoldEvaluationCase(
                "case-1", "game-support:v1", "trend", "玩法 Bug 趋势如何？",
                List.of("玩法 Bug 85 条"), List.of("玩法 Bug 已完全解决"), false);

        when(datasetLoader.load()).thenReturn(new GoldEvaluationDataset("gold:v1", List.of(evaluationCase)));
        when(fixtureLoader.load("game-support:v1")).thenReturn("## 当前数据概览\n玩法 Bug 85 条\n");
        when(literalChatModelCaller.call(anyString(), anyString())).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistant);
        when(assistant.getText()).thenReturn("玩法 Bug 85 条，需要继续排查。");
        when(response.getMetadata()).thenReturn(null);

        GoldEvaluationRunResult result = new GoldEvaluationRunner(
                literalChatModelCaller, datasetLoader, fixtureLoader, new ChatPromptTemplate(),
                new EvaluationCaseScorer(), "qwen-test").run();

        verify(literalChatModelCaller).call(contains("玩法 Bug 85 条"), eq("玩法 Bug 趋势如何？"));
        assertThat(result.datasetVersion()).isEqualTo("gold:v1");
        assertThat(result.promptVersion()).isEqualTo("chat:v5");
        assertThat(result.modelName()).isEqualTo("qwen-test");
        assertThat(result.metrics().totalCaseCount()).isEqualTo(1);
        assertThat(result.metrics().succeededCaseCount()).isEqualTo(1);
        assertThat(result.metrics().factCoverageRate()).isEqualTo(1.0);
        assertThat(result.metrics().evidenceCitationRate()).isEqualTo(0.0);
        assertThat(result.metrics().refusalComplianceRate()).isNull();
        assertThat(result.metrics().p50LatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.metrics().p95LatencyMs()).isGreaterThanOrEqualTo(result.metrics().p50LatencyMs());
        assertThat(result.metrics().p50PromptTokens()).isNull();
        assertThat(result.metrics().p95CompletionTokens()).isNull();
    }
}
