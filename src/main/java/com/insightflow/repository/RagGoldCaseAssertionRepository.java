package com.insightflow.repository;

import com.insightflow.entity.RagGoldCaseAssertion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 金标断言仓储。 */
public interface RagGoldCaseAssertionRepository extends JpaRepository<RagGoldCaseAssertion, Long> {

    List<RagGoldCaseAssertion> findByCaseIdAndWorkspaceIdOrderBySortOrderAsc(
            Long caseId, Long workspaceId);

    List<RagGoldCaseAssertion> findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(
            List<Long> caseIds, Long workspaceId);
}
