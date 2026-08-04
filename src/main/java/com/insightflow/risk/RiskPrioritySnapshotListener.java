package com.insightflow.risk;

import com.insightflow.investigation.AlertCreatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在告警事务提交后冻结风险优先级，避免回滚时遗留孤立排序快照。
 * 监听器与调查任务创建互不依赖，任一后置流程失败都不会回滚已经确认的告警事实。
 */
@Component
public class RiskPrioritySnapshotListener {
    /** 快照服务承担幂等写入和 Workspace 归属校验。 */
    private final RiskPrioritySnapshotService snapshotService;

    public RiskPrioritySnapshotListener(RiskPrioritySnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /** AFTER_COMMIT 中开启新事务，以便重放失败事件时安全复用同一告警快照。 */
    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAlertCreated(AlertCreatedEvent event) {
        snapshotService.recordForAlert(event.workspaceId(), event.alertId());
    }
}
