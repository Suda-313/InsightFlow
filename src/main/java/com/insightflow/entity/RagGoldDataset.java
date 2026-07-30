package com.insightflow.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 运营调查型 RAG 的人工金标数据集版本。
 *
 * <p>内部 {@code id} 仅供子表关联；Runner 与导入脚本只使用 {@code publicId}、{@code datasetKey} 与
 * {@code datasetVersion}。发布或冻结后整包不可变，修正必须新建版本。</p>
 */
@Entity
@Table(name = "rag_gold_dataset")
public class RagGoldDataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 冗余组织键，便于后续组织级统计；写入时从 Workspace 复制，不可由外部 API 单独指定。 */
    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "dataset_key", nullable = false, length = 80, updatable = false)
    private String datasetKey;

    @Column(name = "dataset_version", nullable = false, length = 100, updatable = false)
    private String datasetVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private RagGoldDatasetSplit split;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RagGoldDatasetStatus status;

    /** 标注所依据的语料版本号，便于语料升级后追溯为何需要新数据集版本。 */
    @Column(name = "source_corpus_version", nullable = false, length = 100, updatable = false)
    private String sourceCorpusVersion;

    /** 发布时计算的 SHA-256，覆盖全部 case/evidence/assertion 内容摘要。 */
    @Column(length = 64)
    private String checksum;

    @Column(name = "frozen_at")
    private OffsetDateTime frozenAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RagGoldDataset() {
    }

    /** 创建草稿数据集；仅 DRAFT 状态允许追加题目。 */
    public static RagGoldDataset createDraft(
            Long workspaceId,
            Long organizationId,
            String datasetKey,
            String datasetVersion,
            RagGoldDatasetSplit split,
            String sourceCorpusVersion) {
        validateScope(workspaceId, organizationId, datasetKey, datasetVersion, split, sourceCorpusVersion);
        RagGoldDataset dataset = new RagGoldDataset();
        dataset.publicId = UuidCreator.getTimeOrdered();
        dataset.workspaceId = workspaceId;
        dataset.organizationId = organizationId;
        dataset.datasetKey = datasetKey.trim();
        dataset.datasetVersion = datasetVersion.trim();
        dataset.split = split;
        dataset.status = RagGoldDatasetStatus.DRAFT;
        dataset.sourceCorpusVersion = sourceCorpusVersion.trim();
        dataset.createdAt = OffsetDateTime.now();
        return dataset;
    }

    /** 将草稿发布为不可变快照；checksum 由服务层在发布前计算并传入。 */
    public void publish(String checksum) {
        requireStatus(RagGoldDatasetStatus.DRAFT, "只有草稿数据集可以发布");
        if (checksum == null || checksum.isBlank()) {
            throw new IllegalArgumentException("发布数据集必须携带 checksum");
        }
        this.checksum = checksum.trim();
        this.status = RagGoldDatasetStatus.PUBLISHED;
        this.publishedAt = OffsetDateTime.now();
    }

    /** 将已发布集升级为冻结门禁集。 */
    public void freeze() {
        requireStatus(RagGoldDatasetStatus.PUBLISHED, "只有已发布数据集可以冻结");
        this.status = RagGoldDatasetStatus.FROZEN;
        this.frozenAt = OffsetDateTime.now();
    }

    /** 是否允许追加或修改题目子表。 */
    public boolean isMutable() {
        return status == RagGoldDatasetStatus.DRAFT;
    }

    /** Runner 是否可加载该数据集。 */
    public boolean isRunnable() {
        return status == RagGoldDatasetStatus.PUBLISHED || status == RagGoldDatasetStatus.FROZEN;
    }

    private void requireStatus(RagGoldDatasetStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message);
        }
    }

    private static void validateScope(
            Long workspaceId,
            Long organizationId,
            String datasetKey,
            String datasetVersion,
            RagGoldDatasetSplit split,
            String sourceCorpusVersion) {
        if (workspaceId == null || organizationId == null) {
            throw new IllegalArgumentException("数据集必须绑定 Workspace 与 Organization");
        }
        if (datasetKey == null || datasetKey.isBlank()) {
            throw new IllegalArgumentException("dataset_key 不能为空");
        }
        if (datasetVersion == null || datasetVersion.isBlank()) {
            throw new IllegalArgumentException("dataset_version 不能为空");
        }
        if (split == null) {
            throw new IllegalArgumentException("split 不能为空");
        }
        if (sourceCorpusVersion == null || sourceCorpusVersion.isBlank()) {
            throw new IllegalArgumentException("source_corpus_version 不能为空");
        }
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getOrganizationId() { return organizationId; }
    public String getDatasetKey() { return datasetKey; }
    public String getDatasetVersion() { return datasetVersion; }
    public RagGoldDatasetSplit getSplit() { return split; }
    public RagGoldDatasetStatus getStatus() { return status; }
    public String getSourceCorpusVersion() { return sourceCorpusVersion; }
    public String getChecksum() { return checksum; }
    public OffsetDateTime getFrozenAt() { return frozenAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
