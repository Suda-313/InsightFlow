package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.investigation.InvestigationEvidence;
import com.insightflow.agent.investigation.InvestigationIntent;
import com.insightflow.agent.investigation.InvestigationPlan;
import com.insightflow.agent.investigation.InvestigationPlanner;
import com.insightflow.agent.investigation.InvestigationResult;
import com.insightflow.agent.investigation.InvestigationToolService;
import com.insightflow.agent.investigation.InvestigationToolType;
import com.insightflow.entity.AgentRun;
import com.insightflow.prompt.ChatPromptTemplate;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeSearchTool;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 聊天服务失败路径测试：调查已开始时，模型失败必须收口到同一条 AgentRun Trace。 */
@ExtendWith(OutputCaptureExtension.class)
class ChatServiceTest {

    /** 任何模型运行时异常都不得吞掉，也不得让已开始的审计记录永久停留在 running。 */
    @Test
    void logsTraceAndFailureStatusWhenModelCallThrows(CapturedOutput output) {
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatClient chatClient = mock(ChatClient.class);
        ConversationService conversationService = mock(ConversationService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        InvestigationPlanner planner = mock(InvestigationPlanner.class);
        InvestigationToolService toolService = mock(InvestigationToolService.class);
        KnowledgeSearchTool knowledgeSearchTool = mock(KnowledgeSearchTool.class);
        AgentRun run = AgentRun.start(7L, "chat", "chat:v2", "qwen-test", "tool:v1", "已脱敏问题");
        InvestigationPlan plan = new InvestigationPlan(
                InvestigationIntent.ANOMALY_INVESTIGATION, List.of(InvestigationToolType.ISSUE_TREND));
        InvestigationResult investigation = new InvestigationResult(plan, List.of(new InvestigationEvidence(
                "trend:gameplay:last_14_days",
                InvestigationToolType.ISSUE_TREND,
                "主题趋势",
                "来源 issue_metric_bucket：玩法 Bug 最近 7 天 12 条。",
                true)));

        when(conversationService.recentMessagesForModel(workspaceId, sessionId)).thenReturn(List.of());
        when(agentRunService.start(eq(workspaceId), any(AgentRunService.StartRequest.class))).thenReturn(run);
        when(planner.plan("为什么出现异常？")).thenReturn(plan);
        when(toolService.investigate(workspaceId, "为什么出现异常？", plan)).thenReturn(investigation);
        when(knowledgeSearchTool.retrieve(workspaceId, "为什么出现异常？"))
                .thenReturn(new KnowledgeRetrievalResult(1, List.of()));
        when(chatClient.prompt()).thenThrow(new IllegalStateException("模型不可用"));
        ChatService service = new ChatService(
                chatClient, conversationService, agentRunService, planner, toolService, knowledgeSearchTool,
                new ObjectMapper(), new ChatPromptTemplate());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.chat(workspaceId, sessionId, "为什么出现异常？"))
                .isInstanceOf(IllegalStateException.class);

        verify(agentRunService).fail(eq(workspaceId), eq(run.getPublicId()), any(Long.class));
        assertThat(output).contains("Agent[Investigation]")
                .contains("trace_id=" + run.getPublicId())
                .contains("LLM[Chat]")
                .contains("status=failed");
    }
}
