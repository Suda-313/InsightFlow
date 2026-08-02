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
 * 人工提交的纠错候选，发布前不改变生产规则或历史数据。
 *
 * <p>内部 workspace/case 键用于隔离关联，对外仅使用 publicId。content 是限长的候选描述，不允许保存原始用户反馈或模型思维链；发布需要 Owner 和双评测门禁。</p>
 */
@Entity
@Table(name = "manual_correction")
public class ManualCorrection {
    /** 内部关系主键。 */ @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** 对外纠错 UUID。 */ @Column(name = "public_id", nullable = false, unique = true, updatable = false) private UUID publicId;
    /** Workspace 隔离键。 */ @Column(name = "workspace_id", nullable = false, updatable = false) private Long workspaceId;
    /** 可选调查关联；纠错也可由日常评测发现直接提出。 */ @Column(name = "investigation_case_id", updatable = false) private Long investigationCaseId;
    /** 固定候选类型。 */ @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30, updatable = false) private CorrectionKind kind;
    /** 受控候选文本，限长且不存原始输入。 */ @Column(nullable = false, length = 2000, updatable = false) private String content;
    /** pending_review / published / rejected。 */ @Column(nullable = false, length = 30) private String status;
    /** 提交人公开 UUID。 */ @Column(name = "created_by_public_id", nullable = false, updatable = false) private UUID createdByPublicId;
    /** 通过门禁后的发布时间。 */ @Column(name = "published_at") private OffsetDateTime publishedAt;
    /** 提交时间。 */ @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    /** JPA 专用构造器。 */ protected ManualCorrection() { }
    /** 创建待审候选；发布动作与创建动作严格分离。 */
    public static ManualCorrection pending(Long workspaceId, Long caseId, CorrectionKind kind, String content, UUID creator) {
        ManualCorrection correction = new ManualCorrection();
        correction.publicId = UuidCreator.getTimeOrdered(); correction.workspaceId = workspaceId; correction.investigationCaseId = caseId;
        correction.kind = kind; correction.content = content; correction.status = "pending_review"; correction.createdByPublicId = creator;
        correction.createdAt = OffsetDateTime.now(); return correction;
    }
    /** 双门禁通过后发布；已发布或拒绝的候选不能被静默重复发布。 */
    public void markPublished() { if (!"pending_review".equals(status)) throw new IllegalStateException("纠错候选当前不可发布"); status = "published"; publishedAt = OffsetDateTime.now(); }
    /** 内部主键。 */ public Long getId() { return id; }
    /** 公开 UUID。 */ public UUID getPublicId() { return publicId; }
    /** Workspace 键。 */ public Long getWorkspaceId() { return workspaceId; }
    /** 关联调查键。 */ public Long getInvestigationCaseId() { return investigationCaseId; }
    /** 候选类型。 */ public CorrectionKind getKind() { return kind; }
    /** 受控内容。 */ public String getContent() { return content; }
    /** 发布状态。 */ public String getStatus() { return status; }
    /** 提交人 UUID。 */ public UUID getCreatedByPublicId() { return createdByPublicId; }
    /** 发布时间。 */ public OffsetDateTime getPublishedAt() { return publishedAt; }
    /** 创建时间。 */ public OffsetDateTime getCreatedAt() { return createdAt; }
}
