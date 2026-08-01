package com.insightflow.repository;

import com.insightflow.risk.RiskPrioritySnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 风险快照仓储只暴露按 Workspace 隔离的读取，防止跨工作区查看队列。 */
public interface RiskPrioritySnapshotRepository extends JpaRepository<RiskPrioritySnapshot, Long> {
    Optional<RiskPrioritySnapshot> findByWorkspaceIdAndAlertId(Long workspaceId, Long alertId);
    List<RiskPrioritySnapshot> findByWorkspaceIdOrderByScoreDescCreatedAtDesc(Long workspaceId);
}
