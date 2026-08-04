package com.insightflow.notification;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Alert;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.investigation.AlertCreatedEvent;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.risk.RiskLevel;
import com.insightflow.risk.RiskPriority;
import com.insightflow.risk.RiskPrioritySnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证调查卡片和冻结风险都存在时，监听器才交给邮件服务投递。 */
class RiskEmailNotificationListenerTest {

    private final AlertRepository alertRepository = org.mockito.Mockito.mock(AlertRepository.class);
    private final RiskPrioritySnapshotRepository snapshotRepository = org.mockito.Mockito.mock(RiskPrioritySnapshotRepository.class);
    private final InvestigationCaseRepository caseRepository = org.mockito.Mockito.mock(InvestigationCaseRepository.class);
    private final IssueCatalogRepository issueCatalogRepository = org.mockito.Mockito.mock(IssueCatalogRepository.class);
    private final RiskEmailNotificationService notificationService = org.mockito.Mockito.mock(RiskEmailNotificationService.class);
    private final RiskEmailNotificationListener listener = new RiskEmailNotificationListener(
            alertRepository, snapshotRepository, caseRepository, issueCatalogRepository, notificationService);

    @Test
    void forwardsP1RiskWithInvestigationCardToMailService() {
        Alert alert = Alert.active(7L, 9L, 3L, OffsetDateTime.now(), 30, 10, 2, 8, 5, "{}");
        ReflectionTestUtils.setField(alert, "id", 22L);
        when(alertRepository.findById(22L)).thenReturn(Optional.of(alert));
        RiskPrioritySnapshot snapshot = RiskPrioritySnapshot.create(7L, 22L,
                new RiskPriority(RiskLevel.P1, 75, List.of("影响范围较大")));
        when(snapshotRepository.findByWorkspaceIdAndAlertId(7L, 22L)).thenReturn(Optional.of(snapshot));
        InvestigationCase investigation = InvestigationCase.queued(7L, 22L);
        when(caseRepository.findByWorkspaceIdAndAlertId(7L, 22L)).thenReturn(Optional.of(investigation));
        when(issueCatalogRepository.findById(9L)).thenReturn(Optional.of(IssueCatalog.create(7L, "login_failure", "登录失败")));

        listener.onAlertCreated(new AlertCreatedEvent(7L, 22L, alert.getPublicId()));

        verify(notificationService).sendIfHighRisk(argThat(notification -> notification.level() == RiskLevel.P1
                && notification.issueName().equals("登录失败")
                && notification.investigationId().equals(investigation.getPublicId())));
    }
}
