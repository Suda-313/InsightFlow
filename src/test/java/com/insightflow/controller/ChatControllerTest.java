package com.insightflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.insightflow.agent.investigation.InvestigationEvidence;
import com.insightflow.agent.investigation.InvestigationToolType;
import com.insightflow.entity.ChatMessage;
import com.insightflow.entity.ChatSession;
import com.insightflow.service.ChatService;
import com.insightflow.service.ConversationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 对话 HTTP 契约测试。
 *
 * <p>测试直接调用 Controller，聚焦“API 只传递 public_id 且提供恢复历史的端点”；工作区归属验证由
 * ConversationService 的单元测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private ConversationService conversationService;

    /**
     * 创建会话响应只能公开 UUIDv7，不得把数据库内部会话 id 泄露到 HTTP 边界。
     */
    @Test
    void createsSessionUsingPublicResponse() {
        UUID workspaceId = UUID.randomUUID();
        ChatSession session = ChatSession.create(7L);
        when(conversationService.createSession(workspaceId)).thenReturn(session);

        ChatController.SessionResponse response = new ChatController(chatService, conversationService)
                .createSession(workspaceId);

        assertThat(response.id()).isEqualTo(session.getPublicId());
        assertThat(response.title()).isEqualTo("新会话");
    }

    /**
     * 历史消息响应同时包含消息公共标识、角色、正文和时间，以便刷新页面后原样重建对话。
     */
    @Test
    void returnsPersistedMessagesForSession() {
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatMessage message = ChatMessage.user(7L, 11L, "查看异常主题");
        when(conversationService.listMessages(workspaceId, sessionId)).thenReturn(List.of(message));

        List<ChatController.MessageResponse> response = new ChatController(chatService, conversationService)
                .listMessages(workspaceId, sessionId);

        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(message.getPublicId());
            assertThat(item.role()).isEqualTo("user");
            assertThat(item.content()).isEqualTo("查看异常主题");
        });
    }

    /**
     * 聊天响应必须回传当前模型调用的 Trace，便于客户端与 AgentRun 只读接口关联排障。
     */
    @Test
    void returnsAgentRunTraceWithChatReply() {
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        when(chatService.chat(workspaceId, sessionId, "查看异常主题"))
                .thenReturn(new ChatService.ChatReply(sessionId, traceId, "这是最终回答", List.of(new InvestigationEvidence(
                        "distribution:last_7_days",
                        InvestigationToolType.TOPIC_DISTRIBUTION,
                        "主题分布",
                        "来源 issue_metric_bucket：最近 7 天 Top5。",
                        true))));

        ChatController.ChatResponse response = new ChatController(chatService, conversationService)
                .chat(workspaceId, new ChatController.ChatRequest(sessionId, "查看异常主题"));

        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.traceId()).isEqualTo(traceId);
        assertThat(response.content()).isEqualTo("这是最终回答");
        assertThat(response.evidence()).singleElement().satisfies(item ->
                assertThat(item.id()).isEqualTo("distribution:last_7_days"));
    }
}
