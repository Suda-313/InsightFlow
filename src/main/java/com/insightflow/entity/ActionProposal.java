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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 等待人工审查的处置提案，不等同于已执行命令。
 *
 * <p>提案可由受控调查规则或后续 Agent 产生，但只保存可解释的标题、依据和预览。内部键用于隔离关联，HTTP 仅暴露 publicId；提案本身不会修改调查状态或 Alert。</p>
 */
@Entity
@Table(name = "action_proposal")
public class ActionProposal {

    /** 内部关系主键，不能作为公开处置标识。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外稳定提案 UUID。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** Workspace 内部隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 关联调查内部键，数据库唯一约束确保一种动作只有一条当前提案。 */
    @Column(name = "investigation_case_id", nullable = false, updatable = false)
    private Long investigationCaseId;

    /** 固定枚举动作，不接受客户端随意扩展。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private ProposalAction action;

    /** 人工复核可见的简短提案标题。 */
    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    /** 说明提案依据的受控文本，不能包含模型思维链。 */
    @Column(nullable = false, length = 1000, updatable = false)
    private String rationale;

    /** 可预览影响的服务端生成 JSON，不接收客户端原样写入。 */
    @Column(name = "preview_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String previewJson;

    /** pending / executed / withdrawn 的提案生命周期，不替代执行记录。 */
    @Column(nullable = false, length = 30)
    private String status;

    /** 提案创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 提案状态更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 专用构造器。 */
    protected ActionProposal() {
    }

    /** 创建待审提案；调用方只能传入固定动作和服务端构造的预览。 */
    public static ActionProposal pending(
            Long workspaceId, Long caseId, ProposalAction action, String title, String rationale, String previewJson) {
        ActionProposal proposal = new ActionProposal();
        proposal.publicId = UuidCreator.getTimeOrdered();
        proposal.workspaceId = workspaceId;
        proposal.investigationCaseId = caseId;
        proposal.action = action;
        proposal.title = title;
        proposal.rationale = rationale;
        proposal.previewJson = previewJson;
        proposal.status = "pending";
        proposal.createdAt = OffsetDateTime.now();
        proposal.updatedAt = proposal.createdAt;
        return proposal;
    }

    /** 人工执行成功后提案进入执行态；撤销记录独立保留在 ActionExecution。 */
    public void markExecuted() {
        if (!"pending".equals(status)) {
            throw new IllegalStateException("提案当前不可执行");
        }
        status = "executed";
        updatedAt = OffsetDateTime.now();
    }

    /**
     * 撤销执行后恢复到待复核，而不是生成一条脱离原提案的新命令。
     * 这样审计仍能保留已执行和已撤销的事实，同时人工可以重新预览并确认同一提案。
     */
    public void reopenForReview() {
        if (!"executed".equals(status)) {
            throw new IllegalStateException("仅已执行提案可以恢复待复核");
        }
        status = "pending";
        updatedAt = OffsetDateTime.now();
    }

    /** 内部主键。 */
    public Long getId() { return id; }
    /** 提案公开 UUID。 */
    public UUID getPublicId() { return publicId; }
    /** Workspace 隔离键。 */
    public Long getWorkspaceId() { return workspaceId; }
    /** 调查内部键。 */
    public Long getInvestigationCaseId() { return investigationCaseId; }
    /** 固定处置动作。 */
    public ProposalAction getAction() { return action; }
    /** 提案标题。 */
    public String getTitle() { return title; }
    /** 可解释依据。 */
    public String getRationale() { return rationale; }
    /** 预览 JSON。 */
    public String getPreviewJson() { return previewJson; }
    /** 提案状态。 */
    public String getStatus() { return status; }
    /** 创建时间。 */
    public OffsetDateTime getCreatedAt() { return createdAt; }
    /** 更新时间。 */
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
