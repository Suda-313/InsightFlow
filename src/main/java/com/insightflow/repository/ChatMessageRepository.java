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
    List<ChatMessage> findByWorkspaceIdAndSessionIdOrderByCreatedAtAsc(Long workspaceId, Long sessionId);

    /** 用于模型短期记忆，倒序取有限窗口后由服务层恢复为时间正序。 */
    List<ChatMessage> findTop12ByWorkspaceIdAndSessionIdOrderByCreatedAtDesc(Long workspaceId, Long sessionId);
}
