package com.insightflow.repository;

import com.insightflow.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话消息的持久化端口。
 *
 * <p>所有查询都显式带有 workspace_id 与 session_id，保证消息表不能成为绕过会话授权校验的入口。</p>
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 用于页面恢复，按产生顺序返回会话的最终可见消息。 */
    List<ChatMessage> findByWorkspaceIdAndSessionIdOrderByIdAsc(Long workspaceId, Long sessionId);

    /** 用于模型短期记忆，倒序取有限窗口后由服务层恢复为时间正序。 */
    List<ChatMessage> findTop12ByWorkspaceIdAndSessionIdOrderByIdDesc(Long workspaceId, Long sessionId);

    /** 首次越过短期窗口时，按稳定 identity 读取所有应进入摘要的旧消息。 */
    List<ChatMessage> findByWorkspaceIdAndSessionIdAndIdLessThanOrderByIdAsc(
            Long workspaceId, Long sessionId, Long recentWindowFirstId);

    /** 游标初始化后，仅读取本轮刚滑出短期窗口且尚未消费的消息。 */
    List<ChatMessage> findByWorkspaceIdAndSessionIdAndIdGreaterThanAndIdLessThanOrderByIdAsc(
            Long workspaceId, Long sessionId, Long summaryUntilMessageId, Long recentWindowFirstId);

    /** 统计会话消息总数，用于判断是否需维护滚动摘要。 */
    long countByWorkspaceIdAndSessionId(Long workspaceId, Long sessionId);
}
