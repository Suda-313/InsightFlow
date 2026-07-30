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
 * 企业知识文档的不可覆盖版本。
 *
 * <p>内部 {@code id} 只连接文档和切片表，{@code publicId} 才能进入 HTTP 路径、来源链接和 Agent
 * 证据。正文不放 PostgreSQL：此实体仅保留 MinIO 对象键、校验和和生命周期，避免数据库重复保存原文件。</p>
 */
@Entity
@Table(name = "knowledge_document_version")
public class KnowledgeDocumentVersion {

    /** 内部关系主键，禁止直接暴露给客户端或模型上下文。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外稳定版本标识；同一文档的不同版本永远使用不同 UUIDv7。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 所属文档的内部关系键；文档范围由其自身持有，版本不能迁移到另一篇文档。 */
    @Column(name = "document_id", nullable = false, updatable = false)
    private Long documentId;

    /** 在同一文档内单调递增的业务版本号，和数据库唯一约束共同阻止覆盖历史。 */
    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    /** 控制是否可检索的有限状态机，不允许 Controller 直接写入任意字符串。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeVersionStatus status;

    /** MinIO 内部对象键；只由服务端生成，不能以原文件名或客户端路径作为对象键。 */
    @Column(name = "object_key", nullable = false, length = 500, updatable = false)
    private String objectKey;

    /** 原文件 SHA-256，用于审计同一版本的来源一致性而不记录正文。 */
    @Column(nullable = false, length = 64, updatable = false)
    private String checksum;

    /** 原始文件显示名仅用于受控下载响应，不参与对象键拼接或访问决策。 */
    @Column(name = "source_name", nullable = false, length = 255, updatable = false)
    private String sourceName;

    /** 已验证的 MIME 类型，仅允许 Markdown/TXT，避免把上传扩展名当作可信类型。 */
    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    /** 原始字节数用于下载响应和审计；数据库约束拒绝空文件。 */
    @Column(name = "content_length", nullable = false, updatable = false)
    private long contentLength;

    /** 版本创建时刻与上传完成一致，不能因发布操作改变。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 发布成功时刻；只有切片和嵌入持久化成功后才允许写入。 */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** 被新版本替代或人工下线的时刻，保留供历史引用解释。 */
    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    /** 逻辑删除时刻；不做物理删除，避免既有 AgentRun 的审计证据失去来源。 */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /** 语料原始出处 URL；可为空，用于审计与 Agent 引用溯源，不参与对象键生成。 */
    @Column(name = "source_url", length = 2000, updatable = false)
    private String sourceUrl;

    /** 语料采集或归档时刻；与 created_at（上传完成）区分，便于调查时判断信息新鲜度。 */
    @Column(name = "source_collected_at", updatable = false)
    private OffsetDateTime sourceCollectedAt;

    /** 适用起始时刻；空值表示自发布起始终有效，检索时不做下限过滤。 */
    @Column(name = "effective_from", updatable = false)
    private OffsetDateTime effectiveFrom;

    /** 适用结束时刻；空值表示无明确失效时间，检索时不做上限过滤。 */
    @Column(name = "effective_to", updatable = false)
    private OffsetDateTime effectiveTo;

    /** 语料责任人或维护团队标识；仅用于管理展示与 embed 上下文，不作为权限键。 */
    @Column(length = 100, updatable = false)
    private String owner;

    /**
     * 事实边界说明：本版本可断言的范围与不可推断的内容。
     * 写入 embed 前缀，帮助向量检索区分“可引用事实”与“推测/建议”。
     */
    @Column(name = "fact_boundary", length = 2000, updatable = false)
    private String factBoundary;

    /** 仅供 JPA 映射创建；业务代码必须从 {@link #pending} 建立待审核版本。 */
    protected KnowledgeDocumentVersion() {
    }

    /**
     * 创建尚不可检索的待审核版本。
     *
     * @param documentId 所属文档内部主键，已由服务层完成 Workspace 与 Organization 范围校验
     * @param versionNo 同文档递增版本号，必须从一开始
     * @param objectKey 服务端生成的对象键
     * @param checksum 原文件 SHA-256
     * @param sourceName 用户可见的原文件名
     * @param contentType 校验后的文本 MIME 类型
     * @param contentLength 非空原文件字节数
     * @param metadata 可选语料元数据；全部字段可空，不影响待审核状态机
     * @return 只可等待发布的版本实体
     */
    public static KnowledgeDocumentVersion pending(
            Long documentId, int versionNo, String objectKey, String checksum,
            String sourceName, String contentType, long contentLength, VersionMetadata metadata) {
        if (documentId == null || versionNo < 1 || contentLength < 1) {
            throw new IllegalArgumentException("知识版本的文档、版本号和文件大小必须有效");
        }
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.publicId = UuidCreator.getTimeOrdered();
        version.documentId = documentId;
        version.versionNo = versionNo;
        version.status = KnowledgeVersionStatus.PENDING_REVIEW;
        version.objectKey = objectKey;
        version.checksum = checksum;
        version.sourceName = sourceName;
        version.contentType = contentType;
        version.contentLength = contentLength;
        version.createdAt = OffsetDateTime.now();
        if (metadata != null) {
            version.sourceUrl = metadata.sourceUrl();
            version.sourceCollectedAt = metadata.sourceCollectedAt();
            version.effectiveFrom = metadata.effectiveFrom();
            version.effectiveTo = metadata.effectiveTo();
            version.owner = metadata.owner();
            version.factBoundary = metadata.factBoundary();
        }
        return version;
    }

    /** 无元数据时的便捷重载，保持既有测试与调用方兼容。 */
    public static KnowledgeDocumentVersion pending(
            Long documentId, int versionNo, String objectKey, String checksum,
            String sourceName, String contentType, long contentLength) {
        return pending(documentId, versionNo, objectKey, checksum, sourceName, contentType, contentLength, null);
    }

    /** 上传时可携带的语料元数据；全部可选，服务层负责 trim 与空串归一化。 */
    public record VersionMetadata(
            String sourceUrl,
            OffsetDateTime sourceCollectedAt,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            String owner,
            String factBoundary) {
    }

    /** 仅允许待审核版本在发布物完整构建后变为可检索状态。 */
    public void publish(OffsetDateTime when) {
        requireStatus(KnowledgeVersionStatus.PENDING_REVIEW, "只有待审核版本可以发布");
        this.status = KnowledgeVersionStatus.PUBLISHED;
        this.publishedAt = when;
    }

    /** 已发布版本被替代或下线后必须失效，失效版本永远不能再次参与检索。 */
    public void expire(OffsetDateTime when) {
        requireStatus(KnowledgeVersionStatus.PUBLISHED, "只有已发布版本可以失效");
        this.status = KnowledgeVersionStatus.EXPIRED;
        this.expiredAt = when;
    }

    /** 待审核或已失效版本可以逻辑删除；已发布版本必须先失效以保留当前版本语义。 */
    public void delete(OffsetDateTime when) {
        if (status != KnowledgeVersionStatus.PENDING_REVIEW && status != KnowledgeVersionStatus.EXPIRED) {
            throw new IllegalStateException("只有待审核或已失效版本可以删除");
        }
        this.status = KnowledgeVersionStatus.DELETED;
        this.deletedAt = when;
    }

    /** 状态机统一入口，避免不同生命周期操作产生不一致的异常类型或文案。 */
    private void requireStatus(KnowledgeVersionStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message);
        }
    }

    /** 返回内部版本主键，仅供领域服务和切片持久化使用。 */
    public Long getId() { return id; }

    /** 返回可公开使用的版本 UUID。 */
    public UUID getPublicId() { return publicId; }

    /** 返回内部文档键，不能进入对外 API。 */
    public Long getDocumentId() { return documentId; }

    /** 返回同文档内的业务版本号。 */
    public int getVersionNo() { return versionNo; }

    /** 返回当前检索生命周期状态。 */
    public KnowledgeVersionStatus getStatus() { return status; }

    /** 返回服务端对象键，仅供对象存储适配器使用。 */
    public String getObjectKey() { return objectKey; }

    /** 返回原文件校验和。 */
    public String getChecksum() { return checksum; }

    /** 返回用户可见来源名。 */
    public String getSourceName() { return sourceName; }

    /** 返回已验证的文本 MIME 类型。 */
    public String getContentType() { return contentType; }

    /** 返回原文件字节数。 */
    public long getContentLength() { return contentLength; }

    /** 返回创建审计时刻。 */
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /** 返回发布审计时刻。 */
    public OffsetDateTime getPublishedAt() { return publishedAt; }

    /** 返回失效审计时刻。 */
    public OffsetDateTime getExpiredAt() { return expiredAt; }

    /** 返回逻辑删除审计时刻。 */
    public OffsetDateTime getDeletedAt() { return deletedAt; }

    /** 返回语料原始出处 URL。 */
    public String getSourceUrl() { return sourceUrl; }

    /** 返回语料采集或归档时刻。 */
    public OffsetDateTime getSourceCollectedAt() { return sourceCollectedAt; }

    /** 返回适用起始时刻。 */
    public OffsetDateTime getEffectiveFrom() { return effectiveFrom; }

    /** 返回适用结束时刻。 */
    public OffsetDateTime getEffectiveTo() { return effectiveTo; }

    /** 返回语料责任人标识。 */
    public String getOwner() { return owner; }

    /** 返回事实边界说明。 */
    public String getFactBoundary() { return factBoundary; }
}
