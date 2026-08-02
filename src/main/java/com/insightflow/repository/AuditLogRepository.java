package com.insightflow.repository;

import com.insightflow.entity.AuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 审计事实的工作区隔离读取入口。
 *
 * <p>不提供按 public_id 的全局查询，任何读取都必须携带 workspace_id，防止目标 UUID 被猜测后跨工作区追溯操作历史。</p>
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 读取某业务对象在当前工作区内的完整审计时间线，按创建时间升序保留事件因果顺序。
     */
    List<AuditLog> findByWorkspaceIdAndTargetPublicIdOrderByCreatedAtAsc(Long workspaceId, UUID targetPublicId);
}
