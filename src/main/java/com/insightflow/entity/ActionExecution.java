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
 * 已经由人工确认执行的提案动作及其可撤销状态。
 *
 * <p>每条记录以 workspace_id 与 idempotency_key 唯一，重复请求直接复用同一执行结果。执行记录不存储密码或请求体，操作者只保存公开 UUID；撤销只恢复调查流程状态，不删除任何事实或审计历史。</p>
 */
@Entity
@Table(name = "action_execution")
public class ActionExecution {

    /** 内部关系主键，永不向 API 输出。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外执行 UUID，用于撤销和审计证据引用。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** Workspace 内部隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 目标调查内部键。 */
    @Column(name = "investigation_case_id", nullable = false, updatable = false)
    private Long investigationCaseId;

    /** 来源提案内部键，防止无提案的直接处置。 */
    @Column(name = "action_proposal_id", nullable = false, updatable = false)
    private Long actionProposalId;

    /** 执行人公开 UUID，不使用可猜测的内部账户主键。 */
    @Column(name = "actor_user_public_id", nullable = false, updatable = false)
    private UUID actorUserPublicId;

    /** 客户端幂等键只用于服务端去重，不在 API 响应中暴露。 */
    @Column(name = "idempotency_key", nullable = false, length = 200, updatable = false)
    private String idempotencyKey;

    /** 执行的固定提案动作。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private ProposalAction action;

    /** executed / undone，撤销保留同一行以形成完整命令时间线。 */
    @Column(nullable = false, length = 30)
    private String status;

    /** 受控执行摘要，不保存请求 JSON 或模型输出。 */
    @Column(nullable = false, length = 500, updatable = false)
    private String summary;

    /** 初次执行时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 撤销发生时更新，供调查中心轮询。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 专用构造器。 */
    protected ActionExecution() {
    }

    /** 创建已确认执行记录；角色与提案合法性由命令服务在调用前验证。 */
    public static ActionExecution executed(
            Long workspaceId, UUID casePublicId, UUID proposalPublicId, UUID actorPublicId, String idempotencyKey, String summary) {
        ActionExecution execution = new ActionExecution();
        execution.publicId = UuidCreator.getTimeOrdered();
        execution.workspaceId = workspaceId;
        execution.investigationCaseId = null;
        execution.actionProposalId = null;
        execution.actorUserPublicId = actorPublicId;
        execution.idempotencyKey = idempotencyKey;
        execution.action = ProposalAction.CONFIRM;
        execution.status = "executed";
        execution.summary = summary;
        execution.createdAt = OffsetDateTime.now();
        execution.updatedAt = execution.createdAt;
        return execution;
    }

    /**
     * 生产命令使用内部关联键创建记录；测试辅助工厂不应用于持久化。
     */
    public static ActionExecution executed(
            Long workspaceId, Long caseId, Long proposalId, UUID actorPublicId,
            String idempotencyKey, ProposalAction action, String summary) {
        ActionExecution execution = executed(workspaceId, null, null, actorPublicId, idempotencyKey, summary);
        execution.investigationCaseId = caseId;
        execution.actionProposalId = proposalId;
        execution.action = action;
        return execution;
    }

    /** 撤销只能发生一次，重复撤销不会改变已完成的审计语义。 */
    public void markUndone() {
        if (!"executed".equals(status)) {
            throw new IllegalStateException("当前执行记录不可撤销");
        }
        status = "undone";
        updatedAt = OffsetDateTime.now();
    }

    /** 内部主键。 */ public Long getId() { return id; }
    /** 执行公开 UUID。 */ public UUID getPublicId() { return publicId; }
    /** Workspace 隔离键。 */ public Long getWorkspaceId() { return workspaceId; }
    /** 调查内部键。 */ public Long getInvestigationCaseId() { return investigationCaseId; }
    /** 提案内部键。 */ public Long getActionProposalId() { return actionProposalId; }
    /** 操作者公开 UUID。 */ public UUID getActorUserPublicId() { return actorUserPublicId; }
    /** 幂等键。 */ public String getIdempotencyKey() { return idempotencyKey; }
    /** 固定动作。 */ public ProposalAction getAction() { return action; }
    /** 执行状态。 */ public String getStatus() { return status; }
    /** 安全摘要。 */ public String getSummary() { return summary; }
    /** 创建时间。 */ public OffsetDateTime getCreatedAt() { return createdAt; }
    /** 更新时间。 */ public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
