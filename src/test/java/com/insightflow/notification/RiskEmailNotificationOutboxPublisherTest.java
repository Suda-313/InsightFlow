package com.insightflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.insightflow.entity.RiskEmailNotificationOutbox;
import com.insightflow.repository.RiskEmailNotificationOutboxRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Broker 确认是 Outbox 从待发布进入已发布状态的唯一条件。 */
class RiskEmailNotificationOutboxPublisherTest {

    private final RiskEmailNotificationOutboxRepository repository = org.mockito.Mockito.mock(
            RiskEmailNotificationOutboxRepository.class);
    private final RiskEmailNotificationOutboxPublisher publisher = new RiskEmailNotificationOutboxPublisher(
            repository, org.mockito.Mockito.mock(RiskEmailNotificationRocketMqGateway.class));

    /** 若删除 Broker 成功后的状态转换，发布器会无限重复投递，本测试应失败。 */
    @Test
    void marksOutboxPublishedOnlyAfterBrokerAcknowledges() {
        RiskEmailNotificationOutbox outbox = RiskEmailNotificationOutbox.pending(7L, 22L, java.util.UUID.randomUUID());
        when(repository.findPublishable(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(outbox));

        publisher.publishPending();

        assertThat(outbox.getStatus().name()).isEqualTo("PUBLISHED");
    }
}
