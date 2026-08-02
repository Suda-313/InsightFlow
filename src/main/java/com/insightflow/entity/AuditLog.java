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
 * 人工命令、自动调查和纠错发布的不可变审计事实。
 *
 * <p>内部 {@code id} 和 {@code workspaceId} 仅用于关系与工作区隔离；操作者和目标只保存公开 UUID。摘要必须是受控的业务说明，不能保存请求 JSON、密码、Token 或模型原始上下文。</p>
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    /** 数据库内部关系主键，绝不作为 HTTP、Agent Trace 或前端标识输出。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 审计事件对外可引用的 UUID，避免泄露递增行号。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 事件所属 Workspace 内部隔离键，所有审计读取都必须同时使用它过滤。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 发起动作的账户公开 UUID，不存储内部账户主键。 */
    @Column(name = "actor_user_public_id", nullable = false, updatable = false)
    private UUID actorUserPublicId;

    /** 稳定的点分动作名，例如 proposal.executed，用于报告聚合而不是展示任意用户输入。 */
    @Column(nullable = false, length = 100, updatable = false)
    private String action;

    /** 被审计业务对象的公开 UUID，可关联调查、提案、纠错或报告。 */
    @Column(name = "target_public_id", nullable = false, updatable = false)
    private UUID targetPublicId;

    /** 脱敏且限长的业务摘要，禁止承载原始命令体。 */
    @Column(nullable = false, length = 500, updatable = false)
    private String summary;

    /** 不可变创建时间，作为工作区内审计时间线的唯一排序依据。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 专用构造器；生产代码只能通过受控工厂创建审计事实。 */
    protected AuditLog() {
    }

    /**
     * 创建一次不可变审计事件；调用方必须已经完成授权和敏感摘要过滤。
     */
    public static AuditLog record(
            Long workspaceId, UUID actorUserPublicId, String action, UUID targetPublicId, String summary) {
        AuditLog log = new AuditLog();
        log.publicId = UuidCreator.getTimeOrdered();
        log.workspaceId = workspaceId;
        log.actorUserPublicId = actorUserPublicId;
        log.action = action;
        log.targetPublicId = targetPublicId;
        log.summary = summary;
        log.createdAt = OffsetDateTime.now();
        return log;
    }

    /** 内部关系主键仅供同进程持久化关联使用。 */
    public Long getId() { return id; }

    /** 对外稳定审计标识，可被报告证据链接引用。 */
    public UUID getPublicId() { return publicId; }

    /** 工作区隔离键，仓储查询必须使用该字段阻断跨 Workspace 读取。 */
    public Long getWorkspaceId() { return workspaceId; }

    /** 发起人的公开账户标识，供审计展示但不暴露内部账户行号。 */
    public UUID getActorUserPublicId() { return actorUserPublicId; }

    /** 受控业务动作类型，不能由前端直接拼接写入。 */
    public String getAction() { return action; }

    /** 被审计对象的公开 UUID。 */
    public UUID getTargetPublicId() { return targetPublicId; }

    /** 已脱敏、限长的审计摘要。 */
    public String getSummary() { return summary; }

    /** 审计事件发生时间。 */
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
