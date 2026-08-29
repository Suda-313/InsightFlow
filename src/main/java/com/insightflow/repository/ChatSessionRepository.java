package com.insightflow.repository;

import com.insightflow.entity.ChatSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 对话会话的持久化端口。
 *
 * <p>通过 public_id 查找时必须同时传入 workspace_id；单独按 UUID 查询会让跨工作区越权风险重新出现。</p>
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /** 在所属工作区内精确读取会话，查询不到即视为不存在。 */
    Optional<ChatSession> findByPublicIdAndWorkspaceId(UUID publicId, Long workspaceId);

    /**
     * 仅供 assistant 最终消息落库后的短事务使用，串行化同一会话的摘要和游标更新。
     * LLM、Tool、RAG 等耗时流程必须在获取此锁之前完成。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSession session where session.publicId = :publicId and session.workspaceId = :workspaceId")
    Optional<ChatSession> findByPublicIdAndWorkspaceIdForUpdate(
            @Param("publicId") UUID publicId, @Param("workspaceId") Long workspaceId);

    /** 刷新页面时按最近活动顺序恢复未归档会话。 */
    List<ChatSession> findByWorkspaceIdAndArchivedAtIsNullOrderByUpdatedAtDesc(Long workspaceId);
}
