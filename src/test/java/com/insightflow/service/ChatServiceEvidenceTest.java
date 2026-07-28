package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.insightflow.knowledge.KnowledgeEvidence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 验证聊天主链路只将受控 Tool 证据交给模型，并将同一份证据快照写入审计。 */
class ChatServiceEvidenceTest {

    /** 一次主题异常调查必须保留计划、证据索引和结构化提示词，不能退回静态全量数据拼接。 */
    @Test
    void usesInvestigationEvidenceForPromptAndAuditSnapshot() {
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse response = mock(ChatResponse.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        Generation generation = mock(Generation.class);
        AssistantMessage assistant = mock(AssistantMessage.class);
        ConversationService conversationService = mock(ConversationService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        InvestigationPlanner planner = mock(InvestigationPlanner.class);
        InvestigationToolService toolService = mock(InvestigationToolService.class);
        KnowledgeSearchTool knowledgeSearchTool = mock(KnowledgeSearchTool.class);
        AgentRun run = AgentRun.start(7L, "chat", "chat:v2", "qwen-test", "tool:v1", "已脱敏问题");
        InvestigationPlan plan = new InvestigationPlan(
                InvestigationIntent.ANOMALY_INVESTIGATION,
                List.of(InvestigationToolType.ISSUE_TREND, InvestigationToolType.ALERT_HISTORY));
        InvestigationResult investigation = new InvestigationResult(plan, List.of(
                new InvestigationEvidence(
                        "trend:gameplay:last_14_days", InvestigationToolType.ISSUE_TREND, "主题趋势",
                        "来源 issue_metric_bucket：玩法 Bug 最近 7 天 85 条，前 7 天 40 条。", true),
                new InvestigationEvidence(
                        "alerts:gameplay", InvestigationToolType.ALERT_HISTORY, "告警与基线",
                        "来源 alert：当前值 19、EWMA 9.8、z-score 3.1。", true)));

        when(conversationService.recentMessagesForModel(workspaceId, sessionId)).thenReturn(List.of());
        when(agentRunService.start(eq(workspaceId), any(AgentRunService.StartRequest.class))).thenReturn(run);
        when(planner.plan("玩法Bug 为什么暴增？")).thenReturn(plan);
        when(toolService.investigate(workspaceId, "玩法Bug 为什么暴增？", plan)).thenReturn(investigation);
        KnowledgeEvidence knowledgeEvidence = new KnowledgeEvidence(
                "knowledge:document-a:v1:chunk-a", "版本公告", 1, "7 月版本调整了玩法入口。",
                "/api/v1/workspaces/" + workspaceId + "/knowledge/documents/document-a/versions/version-a/source");
        when(knowledgeSearchTool.retrieve(workspaceId, "玩法Bug 为什么暴增？"))
                .thenReturn(new KnowledgeRetrievalResult(1, List.of(knowledgeEvidence)));
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistant);
        when(assistant.getContent()).thenReturn("## 结论\n反馈增加。\n## 证据\n[证据: trend:gameplay:last_14_days]");

        ChatService service = new ChatService(
                chatClient, conversationService, agentRunService,
                planner, toolService, knowledgeSearchTool, new ObjectMapper(), new ChatPromptTemplate());

        ChatService.ChatReply reply = service.chat(workspaceId, sessionId, "玩法Bug 为什么暴增？");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AgentRunService.Completion> completionCaptor = ArgumentCaptor.forClass(AgentRunService.Completion.class);
        verify(request).system(promptCaptor.capture());
        verify(agentRunService).succeed(eq(workspaceId), eq(run.getPublicId()), completionCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("## 结论")
                .contains("[证据: evidence-id]")
                .contains("trend:gameplay:last_14_days")
                .contains("alerts:gameplay")
                .contains("knowledge:document-a:v1:chunk-a");
        assertThat(completionCaptor.getValue().evidenceJson())
                .contains("trend:gameplay:last_14_days")
                .contains("knowledge:document-a:v1:chunk-a")
                .doesNotContain("issue_id");
        assertThat(reply.evidence()).containsAll(investigation.evidence())
                .anySatisfy(evidence -> {
                    assertThat(evidence.id()).isEqualTo("knowledge:document-a:v1:chunk-a");
                    assertThat(evidence.sourceUrl()).startsWith("/api/v1/workspaces/");
                });
    }
}
