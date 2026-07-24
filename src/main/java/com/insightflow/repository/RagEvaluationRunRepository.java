package com.insightflow.repository;

import com.insightflow.entity.RagEvaluationRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RAG 专项评测批次仓储。
 *
 * <p>public ID 查询始终携带 Workspace 内部键，避免客户端使用一个可猜测或泄漏的 UUID
 * 跨 Workspace 读取评测基线。</p>
 */
public interface RagEvaluationRunRepository extends JpaRepository<RagEvaluationRun, Long> {

    /** 最近一百条仅供当前 Workspace 的 RAG 评测历史。 */
    List<RagEvaluationRun> findTop100ByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    /** 单条读取必须同时命中批次 UUID 和 Workspace 范围。 */
    Optional<RagEvaluationRun> findByPublicIdAndWorkspaceId(UUID publicId, Long workspaceId);
}
