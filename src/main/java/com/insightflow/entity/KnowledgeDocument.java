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
 * 企业知识的逻辑文档。
 *
 * <p>文档负责定义组织和 Workspace 可见范围，版本负责保存生命周期，切片负责检索。内部 {@code id}
 * 只参与三表关联，所有 Controller、引用和 RAG 审计只能携带 {@code publicId}。</p>
 */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    /** 内部关系主键，不得从外部 API 接收或返回。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 文档对外稳定 UUID；同名文档也必须通过 UUID 区分，避免标题被当作权限键。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 知识归属组织的内部键；检索必须先以该键隔离组织。 */
    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    /** 空值代表组织通用；非空只允许引用同组织的当前 Workspace，由服务层在写入前校验。 */
    @Column(name = "target_workspace_id", updatable = false)
    private Long targetWorkspaceId;

    /** 固定业务类型，用于受控检索和 RAG 评测样例分类。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 32, updatable = false)
    private KnowledgeDocumentType documentType;

    /** 用户可读标题；不作为对象键、权限条件或检索范围的唯一依据。 */
    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    /** 文档建立时刻，只用于审计和管理页面排序。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 仅供 JPA 映射创建，业务代码通过范围明确的工厂方法建文档。 */
    protected KnowledgeDocument() {
    }

    /** 创建可供同一组织所有 Workspace 检索的通用文档。 */
    public static KnowledgeDocument organizationCommon(
            Long organizationId, KnowledgeDocumentType documentType, String title) {
        return create(organizationId, null, documentType, title);
    }

    /** 创建只供当前 Workspace 检索的游戏/产品线专属文档。 */
    public static KnowledgeDocument workspaceScoped(
            Long organizationId, Long targetWorkspaceId, KnowledgeDocumentType documentType, String title) {
        if (targetWorkspaceId == null) {
            throw new IllegalArgumentException("工作区专属知识必须指定目标工作区");
        }
        return create(organizationId, targetWorkspaceId, documentType, title);
    }

    /** 两种范围共用初始化逻辑，避免工厂遗漏外部 UUID 或创建审计时间。 */
    private static KnowledgeDocument create(
            Long organizationId, Long targetWorkspaceId, KnowledgeDocumentType documentType, String title) {
        if (organizationId == null || documentType == null || title == null || title.isBlank()) {
            throw new IllegalArgumentException("知识文档的组织、类型和标题不能为空");
        }
        KnowledgeDocument document = new KnowledgeDocument();
        document.publicId = UuidCreator.getTimeOrdered();
        document.organizationId = organizationId;
        document.targetWorkspaceId = targetWorkspaceId;
        document.documentType = documentType;
        document.title = title.trim();
        document.createdAt = OffsetDateTime.now();
        return document;
    }

    /** 返回内部文档键，仅供版本与仓储连接使用。 */
    public Long getId() { return id; }

    /** 返回对外可公开的文档 UUID。 */
    public UUID getPublicId() { return publicId; }

    /** 返回内部组织隔离键。 */
    public Long getOrganizationId() { return organizationId; }

    /** 返回目标 Workspace 内部键；空值表示组织通用。 */
    public Long getTargetWorkspaceId() { return targetWorkspaceId; }

    /** 返回固定知识类型。 */
    public KnowledgeDocumentType getDocumentType() { return documentType; }

    /** 返回用户可读标题。 */
    public String getTitle() { return title; }

    /** 返回创建审计时刻。 */
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /** 判断是否为组织内任意 Workspace 可见的通用文档。 */
    public boolean isOrganizationCommon() { return targetWorkspaceId == null; }
}
