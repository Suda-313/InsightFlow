package com.insightflow.notification;

import com.insightflow.entity.Alert;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.investigation.AlertCreatedEvent;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.risk.RiskPrioritySnapshot;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在告警事务提交后读取已冻结的风险快照和已创建的调查卡片，再调用 Java Mail。
 * 缺少任一事实时宁可跳过，也不发送没有调查入口或等级依据的误导性邮件。
 */
@Component
public class RiskEmailNotificationListener {

    private final AlertRepository alertRepository;
    private final RiskPrioritySnapshotRepository snapshotRepository;
    private final InvestigationCaseRepository caseRepository;
    private final IssueCatalogRepository issueCatalogRepository;
    private final RiskEmailNotificationService notificationService;

    public RiskEmailNotificationListener(
            AlertRepository alertRepository,
            RiskPrioritySnapshotRepository snapshotRepository,
            InvestigationCaseRepository caseRepository,
            IssueCatalogRepository issueCatalogRepository,
            RiskEmailNotificationService notificationService) {
        this.alertRepository = alertRepository;
        this.snapshotRepository = snapshotRepository;
        this.caseRepository = caseRepository;
        this.issueCatalogRepository = issueCatalogRepository;
        this.notificationService = notificationService;
    }

    /** 顺序位于风险快照与调查卡片创建之后，确保邮件引用可复核的冻结事实。 */
    @Order(3)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAlertCreated(AlertCreatedEvent event) {
        Alert alert = alertRepository.findById(event.alertId())
                .filter(value -> event.workspaceId().equals(value.getWorkspaceId()))
                .orElse(null);
        if (alert == null) {
            return;
        }
        RiskPrioritySnapshot snapshot = snapshotRepository
                .findByWorkspaceIdAndAlertId(event.workspaceId(), event.alertId()).orElse(null);
        InvestigationCase investigation = caseRepository
                .findByWorkspaceIdAndAlertId(event.workspaceId(), event.alertId()).orElse(null);
        IssueCatalog issue = issueCatalogRepository.findById(alert.getIssueId())
                .filter(value -> event.workspaceId().equals(value.getWorkspaceId())).orElse(null);
        if (snapshot == null || investigation == null || issue == null) {
            return;
        }
        notificationService.sendIfHighRisk(new RiskEmailNotificationService.Notification(
                snapshot.getLevel(), issue.getCanonicalName(), snapshot.getReasons(), investigation.getPublicId(),
                alert.getCreatedAt()));
    }
}
