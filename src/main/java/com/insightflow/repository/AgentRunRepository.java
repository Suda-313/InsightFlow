package com.insightflow.repository;

import com.insightflow.entity.AgentRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AgentRun 审计记录的持久化端口。
 *
 * <p>公开 Trace 查找必须同时使用 workspace_id；列表限制为最近 100 条，避免审计数据增长后无界加载。</p>
 */
public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    /** 在指定工作区内读取一条公开 Trace。 */
    Optional<AgentRun> findByPublicIdAndWorkspaceId(UUID publicId, Long workspaceId);

    /** 管理端默认只读取最近运行记录；更深历史待具备分页需求后再暴露。 */
    List<AgentRun> findTop100ByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);
}
