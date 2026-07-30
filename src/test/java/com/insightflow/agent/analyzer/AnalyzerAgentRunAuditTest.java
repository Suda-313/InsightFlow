package com.insightflow.agent.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.dto.RiskResult;
import com.insightflow.agent.dto.SentimentResult;
import com.insightflow.entity.AgentRun;
import com.insightflow.prompt.LiteralChatModelCaller;
import com.insightflow.prompt.OperationalPromptCatalog;
import com.insightflow.service.AgentRunService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 分析类 Agent 的失败审计回归测试。
 *
 * <p>模型调用异常不得让 running Trace 残留；测试使用无响应的客户端模拟调用失败，
 * 仅验证受控的 AgentRun 生命周期，不记录异常正文或输入内容。</p>
 */
class AnalyzerAgentRunAuditTest {

    /** 情感分析失败后必须将当前工作区的 Trace 收敛为 failed。 */
    @Test
    void marksSentimentTraceFailedWhenModelCallFails() {
        UUID workspaceId = UUID.randomUUID();
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRun run = AgentRun.start(7L, "sentiment", "sentiment:v1", "qwen-test", "none", "input");
        when(agentRunService.start(eq(workspaceId), any())).thenReturn(run);
        LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
        when(literalChatModelCaller.call(anyString(), anyString())).thenThrow(new IllegalStateException("model down"));
        SentimentAnalyzer analyzer = new SentimentAnalyzer(
                literalChatModelCaller, new ObjectMapper(), new OperationalPromptCatalog(), agentRunService, "qwen-test");

        SentimentResult result = analyzer.execute(workspaceId, "支付失败");

        assertThat(result).isNull();
        verify(agentRunService).fail(eq(workspaceId), eq(run.getPublicId()), any(Long.class));
    }

    /** 风险分析失败后必须将当前工作区的 Trace 收敛为 failed。 */
    @Test
    void marksRiskTraceFailedWhenModelCallFails() {
        UUID workspaceId = UUID.randomUUID();
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRun run = AgentRun.start(7L, "risk", "risk:v1", "qwen-test", "none", "input");
        when(agentRunService.start(eq(workspaceId), any())).thenReturn(run);
        LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
        when(literalChatModelCaller.call(anyString(), anyString())).thenThrow(new IllegalStateException("model down"));
        RiskAnalyzer analyzer = new RiskAnalyzer(
                literalChatModelCaller, new ObjectMapper(), new OperationalPromptCatalog(), agentRunService, "qwen-test");

        RiskResult result = analyzer.execute(workspaceId, "账号被盗");

        assertThat(result).isNull();
        verify(agentRunService).fail(eq(workspaceId), eq(run.getPublicId()), any(Long.class));
    }
}
