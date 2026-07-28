package com.insightflow.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AgentRun;
import com.insightflow.service.AgentRunService;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import com.insightflow.prompt.OperationalPromptCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 验证报告 Agent 能读取 Spring AI 调用链返回的完整报告正文。 */
class ReportAgentTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
    private final AgentRunService agentRunService = mock(AgentRunService.class);
    private final ReportAgent reportAgent = new ReportAgent(
            chatClient, new ReconciliationEngine(), mock(ReportTools.class), new OperationalPromptCatalog(),
            agentRunService, "qwen-test");

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void generatesReportText() {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = mock(AssistantMessage.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        // ReportAgent 使用 Consumer 构造用户消息，不能 mock 字符串重载。
        when(requestSpec.user((Consumer) org.mockito.ArgumentMatchers.any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(assistantMessage.getContent()).thenReturn("report: 100 tickets");

        UUID workspaceId = UUID.randomUUID();
        AgentRun run = AgentRun.start(7L, "report", "report:v1", "qwen-test", "none", "input");
        when(agentRunService.start(org.mockito.ArgumentMatchers.eq(workspaceId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(run);

        String result = reportAgent.generate(workspaceId, new MergedData("summary", 100, Map.of()));

        assertThat(result).isEqualTo("report: 100 tickets");
        verify(agentRunService).succeed(org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.eq(run.getPublicId()), org.mockito.ArgumentMatchers.any());
    }
}
