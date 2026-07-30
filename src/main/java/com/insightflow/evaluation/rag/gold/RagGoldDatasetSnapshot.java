package com.insightflow.evaluation.rag.gold;

import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDatasetStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 已发布/冻结数据集的完整只读快照，供后台 Runner 一次性加载。
 *
 * <p>Runner 应持久化 {@code datasetPublicId}、{@code datasetKey}、{@code datasetVersion}、
 * {@code checksum} 与 {@code caseKey} 列表，以便历史批次可复核。</p>
 */
public record RagGoldDatasetSnapshot(
        UUID datasetPublicId,
        String datasetKey,
        String datasetVersion,
        RagGoldDatasetSplit split,
        RagGoldDatasetStatus status,
        String sourceCorpusVersion,
        String checksum,
        OffsetDateTime publishedAt,
        OffsetDateTime frozenAt,
        List<RagGoldCaseSnapshot> cases) {
}
