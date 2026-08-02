package com.insightflow.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 调查执行时冻结的一条受控证据快照。
 *
 * <p>快照不是数据源的实时视图：后续指标、告警或文档变化不会改写它。内部 case/workspace 键保证关联和隔离；sourceReference 只能是服务端生成的证据标识或公开 UUID，不能包含原始事件 ID。</p>
 */
@Entity
@Table(name = "investigation_evidence_snapshot")
public class InvestigationEvidenceSnapshot {

    /** 内部关系主键，不暴露给 API 或 Prompt。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外可引用的快照 UUID，供调查卡片和证据化报告展示。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 所属调查内部键，读取证据时必须与 workspace_id 双重匹配。 */
    @Column(name = "investigation_case_id", nullable = false, updatable = false)
    private Long investigationCaseId;

    /** 冗余 Workspace 隔离键，避免只凭调查内部键跨租户读取。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 固定 Tool 或 alert 类型，用于说明证据来自何种受控来源。 */
    @Column(name = "source_type", nullable = false, length = 50, updatable = false)
    private String sourceType;

    /** 服务端生成的稳定来源引用，不能是用户自由文本或内部自增 ID。 */
    @Column(name = "source_reference", nullable = false, length = 200, updatable = false)
    private String sourceReference;

    /** 面向人工复核的简短来源标题。 */
    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    /** 已聚合、脱敏和限量的证据正文，不能写入原始 CSV 或模型思维链。 */
    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String content;

    /** false 明确表示数据不足，前端与 Agent 都不得把它解释为确定结论。 */
    @Column(nullable = false, updatable = false)
    private boolean sufficient;

    /** 知识库证据可保存应用内 source URL；指标和告警快照保持 null。 */
    @Column(name = "source_url", length = 500, updatable = false)
    private String sourceUrl;

    /** 快照冻结时间，用于报告复盘还原当时证据。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 专用构造器。 */
    protected InvestigationEvidenceSnapshot() {
    }

    /** 创建受控快照；长度和内容边界由装配器先行保证。 */
    public static InvestigationEvidenceSnapshot capture(
            Long caseId, Long workspaceId, String sourceType, String sourceReference,
            String title, String content, boolean sufficient, String sourceUrl) {
        InvestigationEvidenceSnapshot snapshot = new InvestigationEvidenceSnapshot();
        snapshot.publicId = UuidCreator.getTimeOrdered();
        snapshot.investigationCaseId = caseId;
        snapshot.workspaceId = workspaceId;
        snapshot.sourceType = sourceType;
        snapshot.sourceReference = sourceReference;
        snapshot.title = title;
        snapshot.content = content;
        snapshot.sufficient = sufficient;
        snapshot.sourceUrl = sourceUrl;
        snapshot.createdAt = OffsetDateTime.now();
        return snapshot;
    }

    /** 内部主键。 */
    public Long getId() { return id; }
    /** 对外证据 UUID。 */
    public UUID getPublicId() { return publicId; }
    /** 调查内部键。 */
    public Long getInvestigationCaseId() { return investigationCaseId; }
    /** Workspace 隔离键。 */
    public Long getWorkspaceId() { return workspaceId; }
    /** 受控来源类型。 */
    public String getSourceType() { return sourceType; }
    /** 稳定来源引用。 */
    public String getSourceReference() { return sourceReference; }
    /** 证据标题。 */
    public String getTitle() { return title; }
    /** 证据正文。 */
    public String getContent() { return content; }
    /** 数据是否充足。 */
    public boolean isSufficient() { return sufficient; }
    /** 应用内来源链接。 */
    public String getSourceUrl() { return sourceUrl; }
    /** 冻结时间。 */
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
