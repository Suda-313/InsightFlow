package com.insightflow.notification;

import com.insightflow.entity.RiskEmailNotificationOutbox;
import com.insightflow.repository.RiskEmailNotificationOutboxRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将持久化的邮件意图发布至 RocketMQ；消息只携带 Outbox 公开 ID，邮件事实仍由消费者从库中回读。
 */
@Component
public class RiskEmailNotificationOutboxPublisher {

    private final RiskEmailNotificationOutboxRepository repository;
    private final RiskEmailNotificationRocketMqGateway gateway;

    public RiskEmailNotificationOutboxPublisher(
            RiskEmailNotificationOutboxRepository repository,
            RiskEmailNotificationRocketMqGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    /** Broker 确认后才更新状态；发送异常向上抛出，保留 PENDING 以供下一轮扫描补发。 */
    @Scheduled(fixedDelayString = "${insightflow.notification.rocketmq.publish-interval-ms:5000}")
    @Transactional
    public void publishPending() {
        for (RiskEmailNotificationOutbox outbox : repository.findPublishable(OffsetDateTime.now())) {
            gateway.publish(outbox.getPublicId());
            outbox.markPublished(OffsetDateTime.now());
        }
    }
}
