package com.insightflow.repository;

import com.insightflow.entity.EvaluationRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评测批次快照的持久化端口；公开批次读取必须与 workspace_id 组成复合条件。
 */
public interface EvaluationRunRepository extends JpaRepository<EvaluationRun, Long> {

    /** 在给定工作区内按公开标识读取单批次，避免跨工作区猜测 UUID。 */
    Optional<EvaluationRun> findByPublicIdAndWorkspaceId(UUID publicId, Long workspaceId);

    /** 历史页只读取最近 100 个批次，更多分页需求确认后再扩展。 */
    List<EvaluationRun> findTop100ByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);
}
