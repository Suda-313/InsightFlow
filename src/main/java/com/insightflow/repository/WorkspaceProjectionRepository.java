package com.insightflow.repository;

import com.insightflow.entity.WorkspaceProjection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 自动投影状态的持久化端口。
 *
 * <p>按 asyncTaskId 和 workspaceId 联合读取，避免仅凭任务 UUID 让异步 Worker 跨 Workspace 修改状态。</p>
 */
public interface WorkspaceProjectionRepository extends JpaRepository<WorkspaceProjection, Long> {

    /** 返回指定 Workspace 内归属于任务的唯一投影记录。 */
    Optional<WorkspaceProjection> findByAsyncTaskIdAndWorkspaceId(Long asyncTaskId, Long workspaceId);
}
