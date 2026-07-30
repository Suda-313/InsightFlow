package com.insightflow.evaluation.rag.gold;

import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import java.util.UUID;

/**
 * 一条可接受证据的只读快照；Runner 评分时使用公开 UUID。
 *
 * <p>{@code requirementKey} 相同的多条 evidence 为 OR 组；不同 key（或各自独立 key）之间 AND。</p>
 */
public record RagGoldEvidenceSnapshot(
        RagGoldEvidenceGranularity granularity,
        UUID documentPublicId,
        UUID versionPublicId,
        UUID chunkPublicId,
        String requirementKey) {

    /** 向后兼容：无 requirement_key 时每条 evidence 独立成组。 */
    public RagGoldEvidenceSnapshot(
            RagGoldEvidenceGranularity granularity,
            UUID documentPublicId,
            UUID versionPublicId,
            UUID chunkPublicId) {
        this(granularity, documentPublicId, versionPublicId, chunkPublicId, null);
    }
}
