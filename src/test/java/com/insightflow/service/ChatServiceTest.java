package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.investigation.ChatSessionFocus;
import com.insightflow.agent.investigation.ContextualQueryRewriter;
import com.insightflow.agent.investigation.ConversationFocusExtractor;
import com.insightflow.agent.investigation.InvestigationEvidence;
import com.insightflow.agent.investigation.InvestigationIntent;
import com.insightflow.agent.investigation.InvestigationPlan;
import com.insightflow.agent.investigation.InvestigationPlanner;
import com.insightflow.agent.investigation.InvestigationResult;
import com.insightflow.agent.investigation.InvestigationToolService;
import com.insightflow.agent.investigation.InvestigationToolType;
import com.insightflow.entity.AgentRun;
import com.insightflow.entity.ChatMessage;
import com.insightflow.prompt.ChatPromptTemplate;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeQueryExpander;
import com.insightflow.knowledge.KnowledgeSearchTool;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.insightflow.prompt.LiteralChatModelCaller;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
        LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
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
        when(conversationService.readFocus(workspaceId, sessionId)).thenReturn(ChatSessionFocus.empty());
        when(agentRunService.start(eq(workspaceId), any(AgentRunService.StartRequest.class))).thenReturn(run);
        when(planner.plan("为什么出现异常？")).thenReturn(plan);
        when(toolService.investigate(workspaceId, "为什么出现异常？", plan)).thenReturn(investigation);
        when(knowledgeSearchTool.retrieve(workspaceId, "为什么出现异常？"))
                .thenReturn(new KnowledgeRetrievalResult(1, List.of()));
        when(literalChatModelCaller.call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("模型不可用"));
        ChatService service = chatService(
                literalChatModelCaller, conversationService, agentRunService, planner, toolService, knowledgeSearchTool);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.chat(workspaceId, sessionId, "为什么出现异常？"))
                .isInstanceOf(IllegalStateException.class);

        verify(agentRunService).fail(eq(workspaceId), eq(run.getPublicId()), any(Long.class));
        assertThat(output).contains("Agent[Investigation]")
                .contains("trace_id=" + run.getPublicId())
                .contains("LLM[Chat]")
                .contains("status=failed");
    }

    /**
     * 注入历史的助手消息只保留结论段，不得把上一轮五段式中的建议动作带入 system prompt。
     */
    @Test
    void compressesAssistantHistoryToConclusionOnlyInSystemPrompt() {
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
        ConversationService conversationService = mock(ConversationService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        InvestigationPlanner planner = mock(InvestigationPlanner.class);
        InvestigationToolService toolService = mock(InvestigationToolService.class);
        KnowledgeSearchTool knowledgeSearchTool = mock(KnowledgeSearchTool.class);
        AgentRun run = AgentRun.start(7L, "chat", "chat:v5", "qwen-test", "tool:v1", "已脱敏问题");
        InvestigationPlan plan = new InvestigationPlan(
                InvestigationIntent.ANOMALY_INVESTIGATION, List.of(InvestigationToolType.ISSUE_TREND));
        InvestigationResult investigation = new InvestigationResult(plan, List.of());
        String priorAssistant = """
                ## 结论
                上次认为玩法 Bug 与版本相关。
                ## 证据
                [证据: trend:gameplay]
                ## 建议动作
                历史建议：立即全量回滚版本。
                """;
        ChatMessage historyMessage = ChatMessage.assistant(7L, 9L, priorAssistant);

        when(conversationService.recentMessagesForModel(workspaceId, sessionId)).thenReturn(List.of(historyMessage));
        when(conversationService.readFocus(workspaceId, sessionId)).thenReturn(ChatSessionFocus.empty());
        when(agentRunService.start(eq(workspaceId), any(AgentRunService.StartRequest.class))).thenReturn(run);
        when(planner.plan("继续分析")).thenReturn(plan);
        when(toolService.investigate(workspaceId, "继续分析", plan)).thenReturn(investigation);
        when(knowledgeSearchTool.retrieve(workspaceId, "继续分析"))
                .thenReturn(new KnowledgeRetrievalResult(0, List.of()));
        ChatResponse response = mock(ChatResponse.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        Generation generation = mock(Generation.class);
        AssistantMessage assistant = mock(AssistantMessage.class);
        when(literalChatModelCaller.call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistant);
        when(assistant.getContent()).thenReturn("## 结论\n继续观察。");

        ChatService service = chatService(
                literalChatModelCaller, conversationService, agentRunService, planner, toolService, knowledgeSearchTool);
        service.chat(workspaceId, sessionId, "继续分析");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(literalChatModelCaller).call(promptCaptor.capture(), eq("继续分析"));
        String systemPrompt = promptCaptor.getValue();
        int historyStart = systemPrompt.indexOf("## 最近对话");
        assertThat(historyStart).isGreaterThanOrEqualTo(0);
        String historyBlock = systemPrompt.substring(historyStart);
        assertThat(historyBlock)
                .contains("assistant: 上次认为玩法 Bug 与版本相关。")
                .doesNotContain("## 建议动作")
                .doesNotContain("历史建议：立即全量回滚版本");
    }

    /** 多轮指代时 planner 与检索使用改写 query，用户消息仍存原文。 */
    @Test
    void rewritesQueryForPlannerAndRetrievalWhileStoringOriginalUserMessage() {
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
        ConversationService conversationService = mock(ConversationService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        InvestigationPlanner planner = mock(InvestigationPlanner.class);
        InvestigationToolService toolService = mock(InvestigationToolService.class);
        KnowledgeSearchTool knowledgeSearchTool = mock(KnowledgeSearchTool.class);
        AgentRun run = AgentRun.start(7L, "chat", "chat:v2", "qwen-test", "tool:v1", "已脱敏问题");
        InvestigationPlan plan = new InvestigationPlan(
                InvestigationIntent.ANOMALY_INVESTIGATION, List.of(InvestigationToolType.ISSUE_TREND));
        InvestigationResult investigation = new InvestigationResult(plan, List.of());
        String original = "它为什么涨";
        String rewritten = "登录异常 近14天为什么涨";
        ChatSessionFocus focus = ChatSessionFocus.of("登录异常", "近14天", null);

        when(conversationService.recentMessagesForModel(workspaceId, sessionId)).thenReturn(List.of());
        when(conversationService.readRollingSummary(workspaceId, sessionId)).thenReturn(null);
        when(conversationService.readFocus(workspaceId, sessionId)).thenReturn(focus);
        when(agentRunService.start(eq(workspaceId), any(AgentRunService.StartRequest.class))).thenReturn(run);
        when(planner.plan(rewritten)).thenReturn(plan);
        when(toolService.investigate(workspaceId, rewritten, plan)).thenReturn(investigation);
        when(knowledgeSearchTool.retrieve(workspaceId, rewritten))
                .thenReturn(new KnowledgeRetrievalResult(0, List.of()));
        ChatResponse response = mock(ChatResponse.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        Generation generation = mock(Generation.class);
        AssistantMessage assistant = mock(AssistantMessage.class);
        when(literalChatModelCaller.call(anyString(), eq(original))).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistant);
        when(assistant.getContent()).thenReturn("## 结论\n仍在上涨。");

        ChatService service = chatService(
                literalChatModelCaller, conversationService, agentRunService, planner, toolService, knowledgeSearchTool);
        service.chat(workspaceId, sessionId, original);

        verify(conversationService).appendUserMessage(workspaceId, sessionId, original);
        verify(planner).plan(rewritten);
        verify(toolService).investigate(workspaceId, rewritten, plan);
        verify(knowledgeSearchTool).retrieve(workspaceId, rewritten);
    }

    private ChatService chatService(
            LiteralChatModelCaller literalChatModelCaller,
            ConversationService conversationService,
            AgentRunService agentRunService,
            InvestigationPlanner planner,
            InvestigationToolService toolService,
            KnowledgeSearchTool knowledgeSearchTool) {
        KnowledgeQueryExpander queryExpander = new KnowledgeQueryExpander();
        return new ChatService(
                literalChatModelCaller,
                conversationService,
                agentRunService,
                planner,
                toolService,
                knowledgeSearchTool,
                new ObjectMapper(),
                new ChatPromptTemplate(),
                new ConversationHistoryCompactor(),
                new ContextualQueryRewriter(queryExpander),
                new ConversationFocusExtractor(queryExpander));
    }
}
