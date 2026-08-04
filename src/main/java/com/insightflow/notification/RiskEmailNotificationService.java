package com.insightflow.notification;

import com.insightflow.risk.RiskLevel;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 仅把 P0/P1 调查卡片推送给部署时配置的单一 Owner 邮箱。
 * 邮件不承载原始反馈或模型推理，仅携带冻结风险摘要和回到系统复核的链接。
 */
@Service
public class RiskEmailNotificationService {

    private final JavaMailSender mailSender;
    private final RiskEmailNotificationProperties properties;

    public RiskEmailNotificationService(JavaMailSender mailSender, RiskEmailNotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** P0/P1 才发送；未配置邮箱时明确跳过，便于本地环境在没有 SMTP 的情况下运行。 */
    public void sendIfHighRisk(Notification notification) {
        if (notification == null || !isHighRisk(notification.level())
                || properties.ownerEmail() == null || properties.ownerEmail().isBlank()) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(properties.ownerEmail().trim());
        message.setSubject("[InsightFlow][" + notification.level() + "] 风险调查卡片：" + notification.issueName());
        message.setText("""
                检测到高优风险，请在 InsightFlow 中复核调查卡片。

                风险等级：%s
                主题：%s
                风险原因：%s
                告警时间：%s
                调查卡片：%s
                """.formatted(
                notification.level(), notification.issueName(), notification.reasons(), notification.createdAt(),
                investigationUrl(notification.investigationId())));
        mailSender.send(message);
    }

    private boolean isHighRisk(RiskLevel level) {
        return level == RiskLevel.P0 || level == RiskLevel.P1;
    }

    private String investigationUrl(UUID investigationId) {
        String baseUrl = properties.applicationBaseUrl() == null ? "" : properties.applicationBaseUrl().replaceAll("/+$", "");
        return baseUrl + "/investigations/" + investigationId;
    }

    /** 邮件构造所需的最小脱敏事实；调用方不得传入原始反馈内容。 */
    public record Notification(
            RiskLevel level,
            String issueName,
            String reasons,
            UUID investigationId,
            OffsetDateTime createdAt) {
    }
}
