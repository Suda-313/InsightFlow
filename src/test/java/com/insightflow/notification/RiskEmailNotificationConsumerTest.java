package com.insightflow.notification;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Alert;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.RiskEmailNotificationOutbox;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskEmailNotificationOutboxRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.risk.RiskLevel;
import com.insightflow.risk.RiskPriority;
import com.insightflow.risk.RiskPrioritySnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证 MQ 消费端从冻结事实回读邮件内容，并对已发送 Outbox 幂等跳过。 */
class RiskEmailNotificationConsumerTest {

    private final RiskEmailNotificationOutboxRepository outboxRepository = org.mockito.Mockito.mock(RiskEmailNotificationOutboxRepository.class);
    private final AlertRepository alertRepository = org.mockito.Mockito.mock(AlertRepository.class);
    private final RiskPrioritySnapshotRepository snapshotRepository = org.mockito.Mockito.mock(RiskPrioritySnapshotRepository.class);
    private final InvestigationCaseRepository caseRepository = org.mockito.Mockito.mock(InvestigationCaseRepository.class);
    private final IssueCatalogRepository issueRepository = org.mockito.Mockito.mock(IssueCatalogRepository.class);
    private final RiskEmailNotificationService mailService = org.mockito.Mockito.mock(RiskEmailNotificationService.class);
    private final RiskEmailNotificationConsumer consumer = new RiskEmailNotificationConsumer(
            outboxRepository, alertRepository, snapshotRepository, caseRepository, issueRepository, mailService);

    @Test
    void sendsMailFromFrozenFactsAndMarksOutboxSent() {
        RiskEmailNotificationOutbox outbox = RiskEmailNotificationOutbox.pending(7L, 22L, java.util.UUID.randomUUID());
        when(outboxRepository.findByPublicId(outbox.getPublicId())).thenReturn(Optional.of(outbox));
        Alert alert = Alert.active(7L, 9L, 3L, OffsetDateTime.now(), 30, 10, 2, 8, 5, "{}");
        ReflectionTestUtils.setField(alert, "id", 22L);
        when(alertRepository.findById(22L)).thenReturn(Optional.of(alert));
        when(snapshotRepository.findByWorkspaceIdAndAlertId(7L, 22L)).thenReturn(Optional.of(
                RiskPrioritySnapshot.create(7L, 22L, new RiskPriority(RiskLevel.P1, 75, List.of("影响范围较大")))));
        InvestigationCase investigation = InvestigationCase.queued(7L, 22L);
        when(caseRepository.findByWorkspaceIdAndAlertId(7L, 22L)).thenReturn(Optional.of(investigation));
        when(issueRepository.findById(9L)).thenReturn(Optional.of(IssueCatalog.create(7L, "login_failure", "登录失败")));

        consumer.consume(outbox.getPublicId().toString());

        verify(mailService).sendIfHighRisk(org.mockito.ArgumentMatchers.argThat(notification ->
                notification.level() == RiskLevel.P1 && notification.issueName().equals("登录失败")
                        && notification.investigationId().equals(investigation.getPublicId())));
        org.assertj.core.api.Assertions.assertThat(outbox.getStatus().name()).isEqualTo("SENT");
    }

    @Test
    void skipsAlreadySentOutbox() {
        RiskEmailNotificationOutbox outbox = RiskEmailNotificationOutbox.pending(7L, 22L, java.util.UUID.randomUUID());
        outbox.markSent(OffsetDateTime.now());
        when(outboxRepository.findByPublicId(outbox.getPublicId())).thenReturn(Optional.of(outbox));

        consumer.consume(outbox.getPublicId().toString());

        verify(mailService, never()).sendIfHighRisk(org.mockito.ArgumentMatchers.any());
    }
}
