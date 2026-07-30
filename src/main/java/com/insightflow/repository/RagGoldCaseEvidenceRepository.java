package com.insightflow.repository;

import com.insightflow.entity.RagGoldCaseEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 金标可接受证据仓储。 */
public interface RagGoldCaseEvidenceRepository extends JpaRepository<RagGoldCaseEvidence, Long> {

    List<RagGoldCaseEvidence> findByCaseIdAndWorkspaceIdOrderBySortOrderAsc(
            Long caseId, Long workspaceId);

    List<RagGoldCaseEvidence> findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(
            List<Long> caseIds, Long workspaceId);
}
