package com.insightflow.service;

import com.insightflow.agent.investigation.ChatSessionFocus;
import com.insightflow.common.exception.ChatSessionNotFoundException;
import com.insightflow.entity.ChatMessage;
import com.insightflow.entity.ChatSession;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.ChatMessageRepository;
import com.insightflow.repository.ChatSessionRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话持久化用例：统一处理会话生命周期、消息写入与工作区隔离。
 *
 * <p>本服务不调用模型，也不产生策略动作；Agent 调用方只能借此保存最终消息、读取有限历史，并继续遵守
 * Tool / Guardrail / Trace 边界。</p>
 */
@Service
@Transactional(readOnly = true)
public class ConversationService {

    /** 解析 public_id 到内部隔离键，禁止直接相信 HTTP 层传入的任何内部 id。 */
    private final WorkspaceService workspaceService;

    /** 会话仓储负责按工作区读写会话元数据。 */
    private final ChatSessionRepository sessionRepository;

    /** 消息仓储仅保存最终用户文本和最终助手答案。 */
    private final ChatMessageRepository messageRepository;

    /** 将超出 12 条窗口的更早消息压缩为确定性滚动摘要。 */
    private final SessionRollingSummaryBuilder rollingSummaryBuilder;

    /** 依赖通过构造器注入，保证隔离规则可以在单元测试中独立验证。 */
    public ConversationService(
            WorkspaceService workspaceService,
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            SessionRollingSummaryBuilder rollingSummaryBuilder) {
        this.workspaceService = workspaceService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.rollingSummaryBuilder = rollingSummaryBuilder;
    }

    /** 在指定工作区创建新的活动会话。 */
    @Transactional
    public ChatSession createSession(UUID workspacePublicId) {
        Long workspaceId = resolveWorkspaceId(workspacePublicId);
        return sessionRepository.save(ChatSession.create(workspaceId));
    }

    /** 返回刷新页面可恢复的活动会话，不向客户端暴露归档会话。 */
    public List<ChatSession> listActiveSessions(UUID workspacePublicId) {
        Long workspaceId = resolveWorkspaceId(workspacePublicId);
        return sessionRepository.findByWorkspaceIdAndArchivedAtIsNullOrderByUpdatedAtDesc(workspaceId);
    }

    /**
     * 读取一个会话的完整可见历史；当前首版不做分页，超长会话的分页和摘要待有真实量级后再引入。
     */
    public List<ChatMessage> listMessages(UUID workspacePublicId, UUID sessionPublicId) {
        ChatSession session = requireSession(workspacePublicId, sessionPublicId);
        return messageRepository.findByWorkspaceIdAndSessionIdOrderByIdAsc(
                session.getWorkspaceId(), session.getId());
    }

    /** 用户主动“清空”时归档当前会话，保留最小审计记录而不是物理删除。 */
    @Transactional
    public void archiveSession(UUID workspacePublicId, UUID sessionPublicId) {
        ChatSession session = requireSession(workspacePublicId, sessionPublicId);
        session.archive();
        sessionRepository.save(session);
    }

    /** 保存用户问题并刷新会话活动时间；标题只从第一条用户消息生成。 */
    @Transactional
    public ChatMessage appendUserMessage(UUID workspacePublicId, UUID sessionPublicId, String content) {
        ChatSession session = requireSession(workspacePublicId, sessionPublicId);
        ChatMessage message = ChatMessage.user(session.getWorkspaceId(), session.getId(), content);
        session.updateTitleFromFirstUserMessage(content);
        sessionRepository.save(session);
        return messageRepository.save(message);
    }

    /** 保存模型最终答案；此处的边界确保思维链和中间推理不会进入数据库。 */
    @Transactional
    public ChatMessage appendAssistantMessage(UUID workspacePublicId, UUID sessionPublicId, String content) {
        Long workspaceId = resolveWorkspaceId(workspacePublicId);
        ChatSession session = sessionRepository.findByPublicIdAndWorkspaceIdForUpdate(sessionPublicId, workspaceId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionPublicId));
        ChatMessage message = ChatMessage.assistant(session.getWorkspaceId(), session.getId(), content);
        session.touch();
        messageRepository.saveAndFlush(message);
        refreshRollingSummary(session);
        sessionRepository.save(session);
        return message;
    }

    /** 返回持久化的滚动摘要，供 Prompt 注入；无摘要时 null。 */
    public String readRollingSummary(UUID workspacePublicId, UUID sessionPublicId) {
        return requireSession(workspacePublicId, sessionPublicId).getRollingSummary();
    }

    /**
     * 消息数超过 12 条时，将窗口外消息压缩写入 {@code rolling_summary}；否则清除摘要。
     */
    private void refreshRollingSummary(ChatSession session) {
        long total = messageRepository.countByWorkspaceIdAndSessionId(session.getWorkspaceId(), session.getId());
        if (total <= SessionRollingSummaryBuilder.RECENT_MESSAGE_WINDOW) {
            session.updateRollingSummary(null, null);
            return;
        }
        List<ChatMessage> latestFirst = messageRepository.findTop12ByWorkspaceIdAndSessionIdOrderByIdDesc(
                session.getWorkspaceId(), session.getId());
        if (latestFirst.isEmpty()) {
            return;
        }
        Long recentWindowFirstId = latestFirst.get(latestFirst.size() - 1).getId();
        if (session.getRollingSummary() != null && session.getSummaryUntilMessageId() == null) {
            initializeLegacySummaryCursor(session, recentWindowFirstId);
            return;
        }
        List<ChatMessage> newlyEvicted = findNewlyEvictedMessages(session, recentWindowFirstId);
        if (newlyEvicted.isEmpty()) {
            return;
        }
        String summary = rollingSummaryBuilder.appendIncrementally(session.getRollingSummary(), newlyEvicted);
        session.updateRollingSummary(summary, newlyEvicted.get(newlyEvicted.size() - 1).getId());
    }

    /** 迁移前已有摘要但无游标时，完整重建一次并标记已消费边界，后续恢复增量更新。 */
    private void initializeLegacySummaryCursor(ChatSession session, Long recentWindowFirstId) {
        List<ChatMessage> all = messageRepository.findByWorkspaceIdAndSessionIdOrderByIdAsc(
                session.getWorkspaceId(), session.getId());
        List<ChatMessage> older = all.stream().filter(message -> message.getId() < recentWindowFirstId).toList();
        String summary = rollingSummaryBuilder.buildFromAllMessages(all);
        Long cursor = older.isEmpty() ? null : older.get(older.size() - 1).getId();
        session.updateRollingSummary(summary, cursor);
    }

    /** 通过 nullable 游标区分首次淘汰与后续增量淘汰，不将任意用户输入用于查询边界。 */
    private List<ChatMessage> findNewlyEvictedMessages(ChatSession session, Long recentWindowFirstId) {
        if (session.getSummaryUntilMessageId() == null) {
            return messageRepository.findByWorkspaceIdAndSessionIdAndIdLessThanOrderByIdAsc(
                    session.getWorkspaceId(), session.getId(), recentWindowFirstId);
        }
        return messageRepository.findByWorkspaceIdAndSessionIdAndIdGreaterThanAndIdLessThanOrderByIdAsc(
                session.getWorkspaceId(), session.getId(), session.getSummaryUntilMessageId(), recentWindowFirstId);
    }

    /** 返回最近 12 条正序消息，供 ChatService 注入短期上下文而不无限增长 token。 */
    public List<ChatMessage> recentMessagesForModel(UUID workspacePublicId, UUID sessionPublicId) {
        ChatSession session = requireSession(workspacePublicId, sessionPublicId);
        List<ChatMessage> latestFirst = new ArrayList<>(messageRepository
                .findTop12ByWorkspaceIdAndSessionIdOrderByIdDesc(session.getWorkspaceId(), session.getId()));
        Collections.reverse(latestFirst);
        return latestFirst;
    }

    /** 读取会话级调查焦点，供多轮 query 改写使用。 */
    public ChatSessionFocus readFocus(UUID workspacePublicId, UUID sessionPublicId) {
        return requireSession(workspacePublicId, sessionPublicId).currentFocus();
    }

    /**
     * 持久化本轮抽取到的焦点；空焦点会被 {@link ChatSession#updateFocus} 忽略而不覆盖旧值。
     */
    @Transactional
    public void updateFocusIfNonEmpty(UUID workspacePublicId, UUID sessionPublicId, ChatSessionFocus focus) {
        ChatSession session = requireSession(workspacePublicId, sessionPublicId);
        session.updateFocus(focus);
        sessionRepository.save(session);
    }

    /** 在读取消息或写入消息前完成工作区解析和会话归属校验。 */
    public ChatSession requireSession(UUID workspacePublicId, UUID sessionPublicId) {
        Long workspaceId = resolveWorkspaceId(workspacePublicId);
        return sessionRepository.findByPublicIdAndWorkspaceId(sessionPublicId, workspaceId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionPublicId));
    }

    /** 将外部 UUID 转为可信内部工作区主键，所有公开方法均从这里开始。 */
    private Long resolveWorkspaceId(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        return workspace.getId();
    }
}
