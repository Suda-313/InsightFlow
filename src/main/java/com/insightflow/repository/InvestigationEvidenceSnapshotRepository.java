package com.insightflow.repository;

import com.insightflow.entity.InvestigationEvidenceSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 调查快照仓储将 case 和 Workspace 作为联合查询边界。
 */
public interface InvestigationEvidenceSnapshotRepository extends JpaRepository<InvestigationEvidenceSnapshot, Long> {

    /** 读取调查证据时同时固定 Workspace，防止只猜到 case 内部键便跨范围读取。 */
    List<InvestigationEvidenceSnapshot> findByInvestigationCaseIdAndWorkspaceIdOrderByCreatedAtAsc(
            Long investigationCaseId, Long workspaceId);

    /** Worker 重试前检查是否已有冻结快照，避免重复写入同一次调查依据。 */
    boolean existsByInvestigationCaseIdAndWorkspaceId(Long investigationCaseId, Long workspaceId);
}
