package com.insightflow.notification;

import com.insightflow.investigation.AlertCreatedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在告警事务提交后创建高风险通知 Outbox，而非进程内直接调用 Java Mail。
 * 这样服务在提交后宕机时，发布器恢复后仍可根据 Outbox 继续投递。
 */
@Component
public class RiskEmailNotificationListener {

    private final RiskEmailNotificationOutboxService outboxService;

    public RiskEmailNotificationListener(RiskEmailNotificationOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /** 顺序位于风险快照与调查卡片创建之后，确保邮件引用可复核的冻结事实。 */
    @Order(3)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAlertCreated(AlertCreatedEvent event) {
        outboxService.enqueueIfHighRisk(event);
    }
}
