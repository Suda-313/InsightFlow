package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.common.exception.ChatSessionNotFoundException;
import com.insightflow.entity.ChatSession;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.ChatMessageRepository;
import com.insightflow.repository.ChatSessionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
}
