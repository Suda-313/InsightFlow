package com.insightflow.notification;

import java.util.UUID;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * RocketMQ Producer 适配器；只发送 Outbox 公开标识，消费者必须从数据库回读实际邮件事实。
 */
@Component
public class RocketMqRiskEmailNotificationGateway implements RiskEmailNotificationRocketMqGateway {

    private final RocketMQTemplate rocketMQTemplate;
    private final RiskEmailNotificationRocketMqProperties properties;

    public RocketMqRiskEmailNotificationGateway(
            RocketMQTemplate rocketMQTemplate, RiskEmailNotificationRocketMqProperties properties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.properties = properties;
    }

    /** 同步等待 Broker 确认；调用异常会让 Outbox 保持 PENDING，供后续扫描重试。 */
    @Override
    public void publish(UUID outboxPublicId) {
        rocketMQTemplate.syncSend(properties.topic(), outboxPublicId.toString());
    }
}
