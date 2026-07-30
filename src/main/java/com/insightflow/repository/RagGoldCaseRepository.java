package com.insightflow.repository;

import com.insightflow.entity.RagGoldCase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 金标题目仓储。 */
public interface RagGoldCaseRepository extends JpaRepository<RagGoldCase, Long> {

    List<RagGoldCase> findByDatasetIdAndWorkspaceIdOrderBySortOrderAscCaseKeyAsc(
            Long datasetId, Long workspaceId);

    Optional<RagGoldCase> findByPublicIdAndWorkspaceId(UUID publicId, Long workspaceId);

    boolean existsByDatasetIdAndCaseKey(Long datasetId, String caseKey);
}
