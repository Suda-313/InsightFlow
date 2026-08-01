package com.insightflow.risk;

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
 * 告警产生当刻的风险排序快照；它与不可变 Alert 一一对应，
 * 使后续策略或主题权重变更不会改写当时的运营决策依据。
 */
@Entity
@Table(name = "risk_priority_snapshot")
public class RiskPrioritySnapshot {
    /** 内部关系主键，仅用于数据库关联。 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** 对外稳定 UUID，避免风险队列暴露内部自增键。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;
    /** Workspace 隔离键，所有队列查询必须携带。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;
    /** 对应不可变告警的内部键，数据库唯一约束保证幂等。 */
    @Column(name = "alert_id", nullable = false, unique = true, updatable = false)
    private Long alertId;
    /** 可解释总分，数值越大越优先。 */
    @Column(nullable = false, updatable = false)
    private int score;
    /** 面向运营队列的 P0-P3 分级。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private RiskLevel level;
    /** 受控原因摘要，不保存原始反馈或模型推理。 */
    @Column(nullable = false, length = 1000, updatable = false)
    private String reasons;
    /** 策略版本为未来配置化保留审计锚点。 */
    @Column(name = "policy_version", nullable = false, length = 40, updatable = false)
    private String policyVersion;
    /** 冻结时刻。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RiskPrioritySnapshot() { }

    /** 创建唯一快照；调用方必须先完成 Workspace 与 Alert 归属校验。 */
    public static RiskPrioritySnapshot create(Long workspaceId, Long alertId, RiskPriority priority) {
        RiskPrioritySnapshot snapshot = new RiskPrioritySnapshot();
        snapshot.publicId = UuidCreator.getTimeOrdered();
        snapshot.workspaceId = workspaceId;
        snapshot.alertId = alertId;
        snapshot.score = priority.score();
        snapshot.level = priority.level();
        snapshot.reasons = String.join("；", priority.reasons());
        snapshot.policyVersion = "risk:v1";
        snapshot.createdAt = OffsetDateTime.now();
        return snapshot;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getAlertId() { return alertId; }
    public int getScore() { return score; }
    public RiskLevel getLevel() { return level; }
    public String getReasons() { return reasons; }
    public String getPolicyVersion() { return policyVersion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
