package com.insightflow.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** RocketMQ 发送风险邮件通知所需的非敏感运行配置。 */
@Component
@ConfigurationProperties(prefix = "insightflow.notification.rocketmq")
public record RiskEmailNotificationRocketMqProperties(String topic) {
}
