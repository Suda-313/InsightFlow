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
 * P0/P1 邮件的可靠投递意图。
 *
 * <p>该表与告警事务一起写入，确保“风险已生成但进程在直发邮件前宕机”时仍有可恢复记录。
 * {@code id} 仅用于内部关系，{@code publicId} 可安全用于 MQ 消息；所有读取必须带 {@code workspaceId}。
 * 表中不保存原始反馈、SMTP 凭据或模型内容，消费者会在发送前重新读取冻结事实。</p>
 */
@Entity
@Table(name = "risk_email_notification_outbox")
public class RiskEmailNotificationOutbox {

    /** 内部关系主键，不向 API、消息正文或邮件暴露。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 可发送到 MQ 的稳定公开标识，避免传递可猜测的数据库主键。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** Workspace 隔离键；消息消费者回读数据时必须二次校验。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 对应不可变告警；与 Workspace 组成唯一约束，防止重复事件反复入队。 */
    @Column(name = "alert_id", nullable = false, updatable = false)
    private Long alertId;

    /** 调查卡片公开标识，只用于让消费者定位邮件链接，不表示动态 Owner。 */
    @Column(name = "investigation_public_id", updatable = false)
    private UUID investigationPublicId;

    /** 发布与发送阶段的可恢复状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiskEmailNotificationOutboxStatus status;

    /** Broker 发布尝试次数；不与 RocketMQ 消费重试次数混用。 */
    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    /** 下次允许发布的时间；初始值即创建时间，失败后由发布器延后。 */
    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    /** Broker 已确认接收消息的时刻。 */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** Java Mail 成功返回后的记录时刻；该状态抑制后续重复消费。 */
    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    /** 受控、限长的最后发布错误摘要，禁止记录凭据或敏感反馈。 */
    @Column(name = "last_error", length = 500)
    private String lastError;

    /** 投递意图创建时刻，永不因重试改写。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 状态变更时刻，用于恢复扫描与审计排序。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 专用构造器；业务代码只能使用工厂方法创建待投递记录。 */
    protected RiskEmailNotificationOutbox() {
    }

    /**
     * 创建待发布通知；调用方必须已确认 P0/P1 风险快照与调查卡片同属指定 Workspace。
     */
    public static RiskEmailNotificationOutbox pending(
            Long workspaceId, Long alertId, UUID investigationPublicId) {
        OffsetDateTime now = OffsetDateTime.now();
        RiskEmailNotificationOutbox outbox = new RiskEmailNotificationOutbox();
        outbox.publicId = UuidCreator.getTimeOrdered();
        outbox.workspaceId = workspaceId;
        outbox.alertId = alertId;
        outbox.investigationPublicId = investigationPublicId;
        outbox.status = RiskEmailNotificationOutboxStatus.PENDING;
        outbox.nextAttemptAt = now;
        outbox.createdAt = now;
        outbox.updatedAt = now;
        return outbox;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getAlertId() { return alertId; }
    public UUID getInvestigationPublicId() { return investigationPublicId; }
    public RiskEmailNotificationOutboxStatus getStatus() { return status; }
    public int getPublishAttempts() { return publishAttempts; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /** 仅在 Broker 确认消息后调用，避免把尚未进入 MQ 的记录误认为已投递。 */
    public void markPublished(OffsetDateTime now) {
        status = RiskEmailNotificationOutboxStatus.PUBLISHED;
        publishedAt = now;
        updatedAt = now;
    }

    /** 邮件服务成功返回后标记完成；后续重复消息会被消费者跳过。 */
    public void markSent(OffsetDateTime now) {
        status = RiskEmailNotificationOutboxStatus.SENT;
        sentAt = now;
        updatedAt = now;
    }
}
