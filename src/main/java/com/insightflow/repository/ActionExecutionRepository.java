package com.insightflow.repository;

import com.insightflow.entity.ActionExecution;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 处置执行记录的幂等和撤销读取入口。 */
public interface ActionExecutionRepository extends JpaRepository<ActionExecution, Long> {
    /** 同一 Workspace 下幂等键唯一，重复确认返回原执行结果。 */
    Optional<ActionExecution> findByWorkspaceIdAndIdempotencyKey(Long workspaceId, String idempotencyKey);
    /** 撤销读取必须带 Workspace 范围。 */
    Optional<ActionExecution> findByWorkspaceIdAndPublicId(Long workspaceId, UUID publicId);
    /** 调查详情按时间展示执行与撤销事实，避免前端刷新后丢失操作记录。 */
    List<ActionExecution> findByWorkspaceIdAndInvestigationCaseIdOrderByCreatedAtAsc(Long workspaceId, Long investigationCaseId);
}
