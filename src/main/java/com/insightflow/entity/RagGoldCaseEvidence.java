package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 一道金标题的可接受证据集合中的一条。
 *
 * <p>只保存知识文档/版本/chunk 的公开 UUID，Runner 在评分时再解析并校验 Workspace 可见范围。</p>
 */
@Entity
@Table(name = "rag_gold_case_evidence")
public class RagGoldCaseEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private RagGoldEvidenceGranularity granularity;

    @Column(name = "document_public_id", nullable = false, updatable = false)
    private UUID documentPublicId;

    @Column(name = "version_public_id", updatable = false)
    private UUID versionPublicId;

    @Column(name = "chunk_public_id", updatable = false)
    private UUID chunkPublicId;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    /** 可选需求组键：同组 OR、跨组 AND；null 表示导入时按 sort_order 独立成组。 */
    @Column(name = "requirement_key", length = 120, updatable = false)
    private String requirementKey;

    protected RagGoldCaseEvidence() {
    }

    public static RagGoldCaseEvidence create(
            Long workspaceId,
            Long caseId,
            RagGoldEvidenceGranularity granularity,
            UUID documentPublicId,
            UUID versionPublicId,
            UUID chunkPublicId,
            int sortOrder) {
        return create(workspaceId, caseId, granularity, documentPublicId, versionPublicId, chunkPublicId, sortOrder, null);
    }

    public static RagGoldCaseEvidence create(
            Long workspaceId,
            Long caseId,
            RagGoldEvidenceGranularity granularity,
            UUID documentPublicId,
            UUID versionPublicId,
            UUID chunkPublicId,
            int sortOrder,
            String requirementKey) {
        if (workspaceId == null || caseId == null || granularity == null || documentPublicId == null) {
            throw new IllegalArgumentException("证据必须包含 Workspace、题目、粒度与 document_public_id");
        }
        validateGranularity(granularity, versionPublicId, chunkPublicId);
        RagGoldCaseEvidence evidence = new RagGoldCaseEvidence();
        evidence.workspaceId = workspaceId;
        evidence.caseId = caseId;
        evidence.granularity = granularity;
        evidence.documentPublicId = documentPublicId;
        evidence.versionPublicId = versionPublicId;
        evidence.chunkPublicId = chunkPublicId;
        evidence.sortOrder = sortOrder;
        evidence.requirementKey = requirementKey == null || requirementKey.isBlank() ? null : requirementKey.trim();
        return evidence;
    }

    private static void validateGranularity(
            RagGoldEvidenceGranularity granularity, UUID versionPublicId, UUID chunkPublicId) {
        switch (granularity) {
            case DOCUMENT -> {
                if (versionPublicId != null || chunkPublicId != null) {
                    throw new IllegalArgumentException("DOCUMENT 粒度不应携带 version/chunk 公开 ID");
                }
            }
            case VERSION -> {
                if (versionPublicId == null) {
                    throw new IllegalArgumentException("VERSION 粒度必须携带 version_public_id");
                }
                if (chunkPublicId != null) {
                    throw new IllegalArgumentException("VERSION 粒度不应携带 chunk_public_id");
                }
            }
            case CHUNK -> {
                if (versionPublicId == null || chunkPublicId == null) {
                    throw new IllegalArgumentException("CHUNK 粒度必须同时携带 version_public_id 与 chunk_public_id");
                }
            }
        }
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getCaseId() { return caseId; }
    public RagGoldEvidenceGranularity getGranularity() { return granularity; }
    public UUID getDocumentPublicId() { return documentPublicId; }
    public UUID getVersionPublicId() { return versionPublicId; }
    public UUID getChunkPublicId() { return chunkPublicId; }
    public int getSortOrder() { return sortOrder; }
    public String getRequirementKey() { return requirementKey; }
}
