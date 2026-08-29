package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.insightflow.common.exception.ChatSessionNotFoundException;
import com.insightflow.entity.ChatSession;
import com.insightflow.entity.ChatMessage;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.ChatMessageRepository;
import com.insightflow.repository.ChatSessionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 会话用例的隔离回归测试。
 *
 * <p>这里不连接数据库，而是验证服务层在调用仓储前先解析 Workspace，并且所有会话查询同时带上
 * workspace_id。这样即使客户端猜到其他工作区的 UUID，也不能读取其聊天记录。</p>
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private ChatSessionRepository sessionRepository;

    @Mock
    private ChatMessageRepository messageRepository;

    private final SessionRollingSummaryBuilder rollingSummaryBuilder =
            new SessionRollingSummaryBuilder(new ConversationHistoryCompactor());

    private ConversationService conversationService() {
        return new ConversationService(
                workspaceService, sessionRepository, messageRepository, rollingSummaryBuilder);
    }

    @Mock
    private Workspace workspace;

    /**
     * 新建会话必须绑定服务端解析出的内部工作区主键，而不是相信客户端传入的内部 id。
     */
    @Test
    void createsSessionInsideResolvedWorkspace() {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatSession created = conversationService().createSession(workspacePublicId);

        ArgumentCaptor<ChatSession> sessionCaptor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(created.getPublicId()).isNotNull();
        assertThat(sessionCaptor.getValue().getWorkspaceId()).isEqualTo(7L);
    }

    /**
     * 已归档会话不能作为刷新后的默认会话返回，避免“清空对话”后旧消息重新出现。
     */
    @Test
    void listsOnlyActiveSessionsForWorkspace() {
        UUID workspacePublicId = UUID.randomUUID();
        ChatSession active = ChatSession.create(7L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(sessionRepository.findByWorkspaceIdAndArchivedAtIsNullOrderByUpdatedAtDesc(7L))
                .thenReturn(List.of(active));

        List<ChatSession> sessions = conversationService().listActiveSessions(workspacePublicId);

        assertThat(sessions).containsExactly(active);
        verify(sessionRepository).findByWorkspaceIdAndArchivedAtIsNullOrderByUpdatedAtDesc(7L);
    }

    /**
     * 会话 public_id 在其他工作区存在时也必须视为不存在，防止跨工作区越权读取。
     */
    @Test
    void rejectsSessionThatDoesNotBelongToWorkspace() {
        UUID workspacePublicId = UUID.randomUUID();
        UUID sessionPublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(sessionRepository.findByPublicIdAndWorkspaceId(sessionPublicId, 7L)).thenReturn(Optional.empty());

        ConversationService service = conversationService();

        assertThatThrownBy(() -> service.listMessages(workspacePublicId, sessionPublicId))
                .isInstanceOf(ChatSessionNotFoundException.class);
    }

    /** assistant 落库后只消费游标后的新淘汰消息，并将摘要与游标作为同一会话状态写回。 */
    @Test
    void appendsOnlyMessagesNewlyOutsideRecentWindow() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        ChatSession session = ChatSession.create(7L);
        setId(session, 20L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(sessionRepository.findByPublicIdAndWorkspaceIdForUpdate(session.getPublicId(), 7L))
                .thenReturn(Optional.of(session));
        when(messageRepository.saveAndFlush(any(ChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.countByWorkspaceIdAndSessionId(7L, 20L)).thenReturn(14L);

        ChatMessage first = ChatMessage.user(7L, 20L, "最早问题");
        ChatMessage second = ChatMessage.assistant(7L, 20L, "## 结论\n最早结论");
        setId(first, 101L);
        setId(second, 102L);
        ChatMessage recentWindowFirst = ChatMessage.user(7L, 20L, "最近消息");
        setId(recentWindowFirst, 103L);
        when(messageRepository.findTop12ByWorkspaceIdAndSessionIdOrderByIdDesc(7L, 20L))
                .thenReturn(List.of(recentWindowFirst));
        when(messageRepository.findByWorkspaceIdAndSessionIdAndIdLessThanOrderByIdAsc(7L, 20L, 103L))
                .thenReturn(List.of(first, second));

        conversationService().appendAssistantMessage(workspacePublicId, session.getPublicId(), "本轮回答");

        assertThat(session.getRollingSummary()).isEqualTo("user: 最早问题；assistant: 最早结论");
        assertThat(session.getSummaryUntilMessageId()).isEqualTo(102L);
    }

    /** 刷新重试在游标与短期窗口之间没有新消息时，不能重复追加已经消费的摘要片段。 */
    @Test
    void doesNotAppendAgainWhenNoMessageHasMovedPastCursor() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        ChatSession session = ChatSession.create(7L);
        setId(session, 20L);
        session.updateRollingSummary("user: 已处理的问题", 102L);
        ChatMessage recentWindowFirst = ChatMessage.user(7L, 20L, "最近消息");
        setId(recentWindowFirst, 103L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(sessionRepository.findByPublicIdAndWorkspaceIdForUpdate(session.getPublicId(), 7L))
                .thenReturn(Optional.of(session));
        when(messageRepository.saveAndFlush(any(ChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.countByWorkspaceIdAndSessionId(7L, 20L)).thenReturn(14L);
        when(messageRepository.findTop12ByWorkspaceIdAndSessionIdOrderByIdDesc(7L, 20L))
                .thenReturn(List.of(recentWindowFirst));
        when(messageRepository.findByWorkspaceIdAndSessionIdAndIdGreaterThanAndIdLessThanOrderByIdAsc(7L, 20L, 102L, 103L))
                .thenReturn(List.of());

        conversationService().appendAssistantMessage(workspacePublicId, session.getPublicId(), "重试回答");

        assertThat(session.getRollingSummary()).isEqualTo("user: 已处理的问题");
        assertThat(session.getSummaryUntilMessageId()).isEqualTo(102L);
        verify(messageRepository, never()).findByWorkspaceIdAndSessionIdAndIdLessThanOrderByIdAsc(any(), any(), any());
    }

    /** 迁移前已有摘要而没有游标时，首次刷新用全量 fallback 重新对齐游标，之后才可安全增量。 */
    @Test
    void initializesCursorOnceForLegacySummary() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        ChatSession session = ChatSession.create(7L);
        setId(session, 20L);
        session.updateRollingSummary("迁移前摘要", null);
        List<ChatMessage> all = new java.util.ArrayList<>();
        for (long id = 101L; id <= 114L; id++) {
            ChatMessage message = ChatMessage.user(7L, 20L, "消息" + id);
            setId(message, id);
            all.add(message);
        }
        List<ChatMessage> latestFirst = new java.util.ArrayList<>(all.subList(2, all.size()));
        java.util.Collections.reverse(latestFirst);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(sessionRepository.findByPublicIdAndWorkspaceIdForUpdate(session.getPublicId(), 7L))
                .thenReturn(Optional.of(session));
        when(messageRepository.saveAndFlush(any(ChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.countByWorkspaceIdAndSessionId(7L, 20L)).thenReturn(14L);
        when(messageRepository.findTop12ByWorkspaceIdAndSessionIdOrderByIdDesc(7L, 20L)).thenReturn(latestFirst);
        when(messageRepository.findByWorkspaceIdAndSessionIdOrderByIdAsc(7L, 20L)).thenReturn(all);

        conversationService().appendAssistantMessage(workspacePublicId, session.getPublicId(), "本轮回答");

        assertThat(session.getRollingSummary()).isEqualTo("user: 消息101；user: 消息102");
        assertThat(session.getSummaryUntilMessageId()).isEqualTo(102L);
    }

    /** 测试夹具只写入内部 identity，不模拟数据库生成策略。 */
    private void setId(Object target, Long id) throws Exception {
        Field field = target.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(target, id);
    }
}
