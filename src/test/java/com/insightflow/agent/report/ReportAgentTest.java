package com.insightflow.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AgentRun;
import com.insightflow.prompt.LiteralChatModelCaller;
import com.insightflow.prompt.OperationalPromptCatalog;
import com.insightflow.service.AgentRunService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 验证报告 Agent 能读取模型返回的完整报告正文。 */
class ReportAgentTest {

    private final LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
    private final AgentRunService agentRunService = mock(AgentRunService.class);
    private final ReportAgent reportAgent = new ReportAgent(
            literalChatModelCaller, new ReconciliationEngine(), mock(ReportTools.class), new OperationalPromptCatalog(),
            agentRunService, "qwen-test");

    @Test
    void generatesReportText() {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = mock(AssistantMessage.class);

        when(literalChatModelCaller.call(anyString(), anyString())).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(assistantMessage.getContent()).thenReturn("report: 100 tickets");

        UUID workspaceId = UUID.randomUUID();
        AgentRun run = AgentRun.start(7L, "report", "report:v1", "qwen-test", "none", "input");
        when(agentRunService.start(eq(workspaceId), any())).thenReturn(run);

        String result = reportAgent.generate(workspaceId, new MergedData("summary", 100, Map.of(), Map.of()));

        assertThat(result).isEqualTo("report: 100 tickets");
        verify(agentRunService).succeed(eq(workspaceId), eq(run.getPublicId()), any());
    }
}
