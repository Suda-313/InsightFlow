package com.insightflow.repository;

import com.insightflow.entity.RiskEmailNotificationOutbox;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 邮件 Outbox 仓储；按 Workspace 查询避免消费者利用消息标识跨租户读取通知事实。
 */
public interface RiskEmailNotificationOutboxRepository
        extends JpaRepository<RiskEmailNotificationOutbox, Long> {

    boolean existsByWorkspaceIdAndAlertId(Long workspaceId, Long alertId);

    Optional<RiskEmailNotificationOutbox> findByWorkspaceIdAndPublicId(Long workspaceId, UUID publicId);

    /** 仅供受信任的 MQ 消费入口按消息公开 ID 定位 Outbox；消费者必须随后按实体 Workspace 二次校验所有业务事实。 */
    Optional<RiskEmailNotificationOutbox> findByPublicId(UUID publicId);

    /** 只领取到期的待发布记录，避免每轮扫描重复发送已经获 Broker 确认的消息。 */
    @Query("select outbox from RiskEmailNotificationOutbox outbox "
            + "where outbox.status = com.insightflow.entity.RiskEmailNotificationOutboxStatus.PENDING "
            + "and outbox.nextAttemptAt <= :now order by outbox.createdAt")
    List<RiskEmailNotificationOutbox> findPublishable(@Param("now") OffsetDateTime now);
}
