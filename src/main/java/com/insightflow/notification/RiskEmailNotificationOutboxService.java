package com.insightflow.notification;

import com.insightflow.entity.RiskEmailNotificationOutbox;
import com.insightflow.repository.RiskEmailNotificationOutboxRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.risk.RiskLevel;
import com.insightflow.risk.RiskPrioritySnapshot;
import com.insightflow.investigation.AlertCreatedEvent;
import org.springframework.stereotype.Service;

/**
 * 在告警提交后把高风险邮件意图写入数据库 Outbox。
 *
 * <p>此服务不发送邮件也不访问 RocketMQ；它只基于已冻结的风险快照和调查卡片写入可恢复事实，
 * 因而应用在发布消息前宕机时，后续发布器仍可扫描并补发。</p>
 */
@Service
public class RiskEmailNotificationOutboxService {

    private final RiskPrioritySnapshotRepository snapshotRepository;
    private final RiskEmailNotificationOutboxRepository outboxRepository;

    public RiskEmailNotificationOutboxService(
            RiskPrioritySnapshotRepository snapshotRepository,
            RiskEmailNotificationOutboxRepository outboxRepository) {
        this.snapshotRepository = snapshotRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * P0/P1 且调查卡片已创建时才入队；同一 Workspace/Alert 已有记录则静默跳过，
     * 以处理事件重放和事务重试。数据库唯一约束仍是并发场景下的最终护栏。
     */
    public void enqueueIfHighRisk(AlertCreatedEvent event) {
        RiskPrioritySnapshot snapshot = snapshotRepository
                .findByWorkspaceIdAndAlertId(event.workspaceId(), event.alertId())
                .orElse(null);
        if (!isHighRisk(snapshot) || outboxRepository.existsByWorkspaceIdAndAlertId(event.workspaceId(), event.alertId())) {
            return;
        }
        outboxRepository.save(RiskEmailNotificationOutbox.pending(
                event.workspaceId(), event.alertId(), null));
    }

    /** 仅 P0/P1 触发外部提醒，低优风险保留在站内队列，避免邮件噪音。 */
    private boolean isHighRisk(RiskPrioritySnapshot snapshot) {
        return snapshot != null && (snapshot.getLevel() == RiskLevel.P0 || snapshot.getLevel() == RiskLevel.P1);
    }
}
