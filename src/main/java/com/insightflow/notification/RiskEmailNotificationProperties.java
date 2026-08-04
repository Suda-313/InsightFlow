package com.insightflow.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 单一 Owner 邮箱通知的部署配置。邮箱与 SMTP 密钥均来自受控环境变量，
 * 不把个人邮箱或凭据写入 Workspace 业务数据、日志和默认配置。
 */
@Component
@ConfigurationProperties(prefix = "insightflow.notification")
public record RiskEmailNotificationProperties(String ownerEmail, String applicationBaseUrl) {
}
