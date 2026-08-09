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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 由一个不可变告警触发、可异步推进并等待人工复核的调查卡片。
 *
 * <p>内部 {@code id}/{@code workspaceId}/{@code alertId} 只用于关系和隔离；API 仅暴露 {@code publicId}。该实体不修改 Alert，所有后续结论和处置必须关联本卡片，确保触发事实与流程状态可分别追溯。</p>
 */
@Entity
@Table(name = "investigation_case")
public class InvestigationCase {

    /** 内部关系主键，禁止进入 API、审计摘要或模型上下文。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外稳定调查标识，用于调查中心、提案和报告证据链接。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 所属 Workspace 内部隔离键，所有读取和写入都要使用其二次过滤。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 触发本调查的不可变告警内部键，数据库唯一约束防止重复调查同一告警。 */
    @Column(name = "alert_id", nullable = false, updatable = false)
    private Long alertId;

    /** 异步任务内部键仅用于 Worker 领取与完成校验，永不向外部返回。 */
    @Column(name = "async_task_id", unique = true)
    private Long asyncTaskId;

    /**
     * 首次规划后冻结的调查窗口与 Planner 审计摘要。
     *
     * <p>该 JSON 属于 InvestigationCase 聚合而非可重试 AsyncTask payload；Worker 只能读取它，
     * 因而租约恢复、进程重启和人工重试都不能改变已选窗口。</p>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_json", columnDefinition = "jsonb")
    private String planJson;

    /** queued / investigating / pending_review / confirmed / failed 的显式可观察流程状态。 */
    @Column(nullable = false, length = 30)
    private String status;

    /** 由受控证据装配生成的简短结论边界，不存储模型思维链或原始反馈文本。 */
    @Column(length = 1000)
    private String summary;

    /** Worker 失败时对用户可见的受控错误码，不记录异常类型或堆栈。 */
    @Column(name = "error_code", length = 80)
    private String errorCode;

    /** Worker 失败时的限长、安全摘要。 */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * 最小响应闭环的独立状态；它不替代调查取证状态，
     * 仅回答当前异常是否已被团队成员开始跟进。
     */
    @Column(name = "follow_up_status", nullable = false, length = 30)
    private String followUpStatus;

    /**
     * 首位开始跟进成员的公开 UUID；不把它解释为排他责任人，
     * 后续协作成员仍可读取、复核和执行既有处置提案。
     */
    @Column(name = "follow_up_by_user_public_id")
    private UUID followUpByUserPublicId;

    /**
     * 首次开始跟进的时间，用于计算未响应提醒而不是考勤或绩效用途。
     */
    @Column(name = "follow_up_started_at")
    private OffsetDateTime followUpStartedAt;

    /**
     * 最近一次站内提醒时间；它只用于限制重复提醒频率，
     * 不替代原始告警时间，也不表示已经有人实际处理。
     */
    @Column(name = "follow_up_reminder_at")
    private OffsetDateTime followUpReminderAt;

    /** 调查受理时间，不因重试或人工操作改写。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 卡片状态或摘要变更时间，用于前端轮询和审计排序。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 专用构造器；业务代码必须使用排队工厂创建。 */
    protected InvestigationCase() {
    }

    /**
     * 创建尚未绑定任务的调查卡片；绑定任务在同一事务内完成，避免 Worker 看到半成品卡片。
     */
    public static InvestigationCase queued(Long workspaceId, Long alertId) {
        InvestigationCase investigation = new InvestigationCase();
        investigation.publicId = UuidCreator.getTimeOrdered();
        investigation.workspaceId = workspaceId;
        investigation.alertId = alertId;
        investigation.status = "queued";
        investigation.followUpStatus = "awaiting_follow_up";
        investigation.createdAt = OffsetDateTime.now();
        investigation.updatedAt = investigation.createdAt;
        return investigation;
    }

    /** 关联唯一异步任务；重复绑定表示调用顺序错误，必须立即拒绝。 */
    public void attachTask(Long taskId) {
        if (asyncTaskId != null || taskId == null) {
            throw new IllegalStateException("调查任务绑定状态不合法");
        }
        asyncTaskId = taskId;
        touch();
    }

    /**
     * 冻结首次规划的受控 JSON。重复写入意味着重试试图改变同一 Alert 的证据范围，必须拒绝。
     */
    public void freezePlan(String frozenPlanJson) {
        if (frozenPlanJson == null || frozenPlanJson.isBlank()) {
            throw new IllegalArgumentException("调查计划不能为空");
        }
        if (planJson != null) {
            throw new IllegalStateException("调查计划已冻结，不能覆盖");
        }
        planJson = frozenPlanJson;
        touch();
    }

    /** Worker 成功领到任务后进入调查中；终态任务不能被旧 Worker 覆盖。 */
    public void markInvestigating() {
        if (!"queued".equals(status) && !"investigating".equals(status)) {
            throw new IllegalStateException("当前调查不能开始执行");
        }
        status = "investigating";
        touch();
    }

    /** 证据快照写入完成后等待人工确认，禁止自动把调查结论视作已处置。 */
    public void markPendingReview(String controlledSummary) {
        status = "pending_review";
        summary = controlledSummary;
        errorCode = null;
        errorMessage = null;
        touch();
    }

    /** 人工确认调查结果后才可成为报告和纠错门禁的有效证据。 */
    public void markConfirmed() {
        if (!"pending_review".equals(status)) {
            throw new IllegalStateException("只有待复核调查可以确认");
        }
        status = "confirmed";
        touch();
    }

    /** 人工忽略只改变调查流程，不删除告警、快照或此前审计记录。 */
    public void markIgnored() {
        if (!"pending_review".equals(status)) {
            throw new IllegalStateException("只有待复核调查可以忽略");
        }
        status = "ignored";
        touch();
    }

    /** 关闭只适用于已确认调查，避免未复核证据被直接视为解决。 */
    public void markClosed() {
        if (!"confirmed".equals(status)) {
            throw new IllegalStateException("只有已确认调查可以关闭");
        }
        status = "closed";
        touch();
    }

    /** 撤销处置回到待人工复核，不尝试改写不可变触发事实。 */
    public void reopenForReview() {
        if (!"confirmed".equals(status) && !"ignored".equals(status) && !"closed".equals(status)) {
            throw new IllegalStateException("当前调查没有可撤销的处置");
        }
        status = "pending_review";
        touch();
    }

    /** 异步失败只写入安全摘要，并保留原始 Alert 与此前快照用于复盘。 */
    public void markFailed(String code, String message) {
        status = "failed";
        errorCode = code;
        errorMessage = message;
        touch();
    }

    /**
     * 记录团队中首位开始跟进的成员；重复操作保持首位记录不变，
     * 从而避免在未实现派单模型时悄然产生责任人被覆盖的语义。
     */
    public void startFollowUp(UUID actorPublicId) {
        if (actorPublicId == null) {
            throw new IllegalArgumentException("跟进成员不能为空");
        }
        if ("awaiting_follow_up".equals(followUpStatus)) {
            followUpStatus = "in_follow_up";
            followUpByUserPublicId = actorPublicId;
            followUpStartedAt = OffsetDateTime.now();
            followUpReminderAt = null;
            touch();
        }
    }

    /** 仅未开始跟进的卡片可被标记提醒；调用方负责根据 SLA 过滤候选集。 */
    public void markFollowUpReminder() {
        if ("awaiting_follow_up".equals(followUpStatus)) {
            followUpReminderAt = OffsetDateTime.now();
            touch();
        }
    }

    /** 所有可变流程字段统一更新时间，保证轮询读取语义一致。 */
    private void touch() { updatedAt = OffsetDateTime.now(); }

    /** 内部关系主键。 */
    public Long getId() { return id; }
    /** 对外调查 UUID。 */
    public UUID getPublicId() { return publicId; }
    /** Workspace 内部隔离键。 */
    public Long getWorkspaceId() { return workspaceId; }
    /** 触发 Alert 内部键。 */
    public Long getAlertId() { return alertId; }
    /** 异步任务内部键。 */
    public Long getAsyncTaskId() { return asyncTaskId; }
    /** 已冻结调查计划 JSON；为空表示尚未完成首次规划，Worker 不得自行推导窗口。 */
    public String getPlanJson() { return planJson; }
    /** 当前流程状态。 */
    public String getStatus() { return status; }
    /** 可展示的受控摘要。 */
    public String getSummary() { return summary; }
    /** 安全失败码。 */
    public String getErrorCode() { return errorCode; }
    /** 安全失败摘要。 */
    public String getErrorMessage() { return errorMessage; }
    /** 响应闭环状态，不等同于调查取证状态。 */
    public String getFollowUpStatus() { return followUpStatus; }
    /** 首位开始跟进者的公开 UUID。 */
    public UUID getFollowUpByUserPublicId() { return followUpByUserPublicId; }
    /** 首次开始跟进时间。 */
    public OffsetDateTime getFollowUpStartedAt() { return followUpStartedAt; }
    /** 最近一次站内提醒时间。 */
    public OffsetDateTime getFollowUpReminderAt() { return followUpReminderAt; }
    /** 创建时间。 */
    public OffsetDateTime getCreatedAt() { return createdAt; }
    /** 最近变更时间。 */
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
