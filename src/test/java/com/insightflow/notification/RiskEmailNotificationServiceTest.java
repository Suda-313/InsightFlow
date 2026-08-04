package com.insightflow.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.insightflow.risk.RiskLevel;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

/** 验证只有高优风险才会投递调查卡片邮件，避免普通告警淹没 Owner。 */
class RiskEmailNotificationServiceTest {

    private final JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
    private final RiskEmailNotificationService service = new RiskEmailNotificationService(
            mailSender, new RiskEmailNotificationProperties("owner@example.com", "https://insightflow.example.com"));

    @Test
    void sendsInvestigationCardForP0Risk() {
        service.sendIfHighRisk(new RiskEmailNotificationService.Notification(
                RiskLevel.P0, "登录失败", "异常强度高", UUID.randomUUID(), OffsetDateTime.parse("2026-08-03T08:00:00Z")));

        verify(mailSender).send(any(org.springframework.mail.SimpleMailMessage.class));
    }

    @Test
    void skipsEmailForP2Risk() {
        service.sendIfHighRisk(new RiskEmailNotificationService.Notification(
                RiskLevel.P2, "登录失败", "异常强度高", UUID.randomUUID(), OffsetDateTime.parse("2026-08-03T08:00:00Z")));

        verify(mailSender, never()).send(any(org.springframework.mail.SimpleMailMessage.class));
    }
}
