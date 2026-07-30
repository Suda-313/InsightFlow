package com.insightflow.repository;

import com.insightflow.entity.RagGoldDataset;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDatasetStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 人工金标数据集仓储；所有读取必须带 workspaceId 防止跨工作区泄露。 */
public interface RagGoldDatasetRepository extends JpaRepository<RagGoldDataset, Long> {

    Optional<RagGoldDataset> findByPublicIdAndWorkspaceId(UUID publicId, Long workspaceId);

    Optional<RagGoldDataset> findByWorkspaceIdAndDatasetKeyAndDatasetVersion(
            Long workspaceId, String datasetKey, String datasetVersion);

    List<RagGoldDataset> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(
            Long workspaceId, RagGoldDatasetStatus status);

    List<RagGoldDataset> findByWorkspaceIdAndStatusAndSplitOrderByCreatedAtDesc(
            Long workspaceId, RagGoldDatasetStatus status, RagGoldDatasetSplit split);
}
