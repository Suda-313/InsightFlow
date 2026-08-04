package com.insightflow.notification;

import com.insightflow.entity.Alert;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.RiskEmailNotificationOutbox;
import com.insightflow.entity.RiskEmailNotificationOutboxStatus;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskEmailNotificationOutboxRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.risk.RiskPrioritySnapshot;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** RocketMQ 消费者：只接收 Outbox ID，重新读取受控事实后发送脱敏邮件。 */
@Component
@RocketMQMessageListener(
        topic = "${insightflow.notification.rocketmq.topic}",
        consumerGroup = "${insightflow.notification.rocketmq.consumer-group:insightflow-risk-email}")
public class RiskEmailNotificationConsumer implements RocketMQListener<String> {
    private final RiskEmailNotificationOutboxRepository outboxRepository;
    private final AlertRepository alertRepository;
    private final RiskPrioritySnapshotRepository snapshotRepository;
    private final InvestigationCaseRepository caseRepository;
    private final IssueCatalogRepository issueRepository;
    private final RiskEmailNotificationService mailService;

    public RiskEmailNotificationConsumer(RiskEmailNotificationOutboxRepository outboxRepository, AlertRepository alertRepository,
            RiskPrioritySnapshotRepository snapshotRepository, InvestigationCaseRepository caseRepository,
            IssueCatalogRepository issueRepository, RiskEmailNotificationService mailService) {
        this.outboxRepository = outboxRepository; this.alertRepository = alertRepository;
        this.snapshotRepository = snapshotRepository; this.caseRepository = caseRepository;
        this.issueRepository = issueRepository; this.mailService = mailService;
    }

    @Override public void onMessage(String payload) { consume(payload); }

    /** SMTP 异常不吞掉，使 RocketMQ 执行至少一次重试。 */
    @Transactional
    public void consume(String payload) {
        RiskEmailNotificationOutbox outbox = outboxRepository.findByPublicId(UUID.fromString(payload)).orElse(null);
        if (outbox == null || outbox.getStatus() == RiskEmailNotificationOutboxStatus.SENT) return;
        Alert alert = alertRepository.findById(outbox.getAlertId()).filter(a -> a.getWorkspaceId().equals(outbox.getWorkspaceId())).orElse(null);
        RiskPrioritySnapshot snapshot = snapshotRepository.findByWorkspaceIdAndAlertId(outbox.getWorkspaceId(), outbox.getAlertId()).orElse(null);
        InvestigationCase investigation = caseRepository.findByWorkspaceIdAndAlertId(outbox.getWorkspaceId(), outbox.getAlertId()).orElse(null);
        IssueCatalog issue = alert == null ? null : issueRepository.findById(alert.getIssueId()).filter(i -> i.getWorkspaceId().equals(outbox.getWorkspaceId())).orElse(null);
        if (alert == null || snapshot == null || investigation == null || issue == null) {
            throw new IllegalStateException("风险邮件依赖的冻结事实尚未就绪");
        }
        mailService.sendIfHighRisk(new RiskEmailNotificationService.Notification(snapshot.getLevel(), issue.getCanonicalName(), snapshot.getReasons(), investigation.getPublicId(), alert.getCreatedAt()));
        outbox.markSent(OffsetDateTime.now());
    }
}
