package com.insightflow.notification;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.RiskEmailNotificationOutboxStatus;
import com.insightflow.investigation.AlertCreatedEvent;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.RiskEmailNotificationOutboxRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.risk.RiskLevel;
import com.insightflow.risk.RiskPriority;
import com.insightflow.risk.RiskPrioritySnapshot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证告警提交后只把高风险的可靠投递意图写入 Outbox，不直接触发外部邮件。 */
class RiskEmailNotificationOutboxServiceTest {

    private final RiskPrioritySnapshotRepository snapshotRepository = org.mockito.Mockito.mock(RiskPrioritySnapshotRepository.class);
    private final RiskEmailNotificationOutboxRepository outboxRepository = org.mockito.Mockito.mock(RiskEmailNotificationOutboxRepository.class);
    private final RiskEmailNotificationOutboxService service = new RiskEmailNotificationOutboxService(
            snapshotRepository, outboxRepository);

    /** 删除 P0/P1 分支或错误写入非 PENDING 状态时，本测试应失败。 */
    @Test
    void createsPendingOutboxForP1RiskWithInvestigation() {
        AlertCreatedEvent event = new AlertCreatedEvent(7L, 22L, java.util.UUID.randomUUID());
        when(snapshotRepository.findByWorkspaceIdAndAlertId(7L, 22L)).thenReturn(Optional.of(snapshot(RiskLevel.P1)));
        when(outboxRepository.existsByWorkspaceIdAndAlertId(7L, 22L)).thenReturn(false);

        service.enqueueIfHighRisk(event);

        verify(outboxRepository).save(argThat(outbox -> outbox.getWorkspaceId().equals(7L)
                && outbox.getAlertId().equals(22L)
                && outbox.getInvestigationPublicId() == null
                && outbox.getStatus() == RiskEmailNotificationOutboxStatus.PENDING));
    }

    /** 删除等级边界会让 P2 误入通知队列；该行为必须被拒绝。 */
    @Test
    void skipsP2Risk() {
        AlertCreatedEvent event = new AlertCreatedEvent(7L, 22L, java.util.UUID.randomUUID());
        when(snapshotRepository.findByWorkspaceIdAndAlertId(7L, 22L)).thenReturn(Optional.of(snapshot(RiskLevel.P2)));

        service.enqueueIfHighRisk(event);

        verify(outboxRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private RiskPrioritySnapshot snapshot(RiskLevel level) {
        return RiskPrioritySnapshot.create(7L, 22L, new RiskPriority(level, 75, List.of("影响范围较大")));
    }
}
