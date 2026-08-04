package com.insightflow.notification;

import static org.mockito.Mockito.verify;

import com.insightflow.investigation.AlertCreatedEvent;
import org.junit.jupiter.api.Test;

/** 监听器只负责在事务提交后委托 Outbox 服务，不能退化为同步直发邮件。 */
class RiskEmailNotificationListenerTest {

    private final RiskEmailNotificationOutboxService outboxService = org.mockito.Mockito.mock(
            RiskEmailNotificationOutboxService.class);
    private final RiskEmailNotificationListener listener = new RiskEmailNotificationListener(outboxService);

    /** 删除委托会让已提交的告警没有持久化通知意图，本测试应失败。 */
    @Test
    void delegatesCommittedAlertToOutboxService() {
        AlertCreatedEvent event = new AlertCreatedEvent(7L, 22L, java.util.UUID.randomUUID());

        listener.onAlertCreated(event);

        verify(outboxService).enqueueIfHighRisk(event);
    }
}
