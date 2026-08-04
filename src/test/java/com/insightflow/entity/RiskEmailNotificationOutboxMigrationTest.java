package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 风险邮件要在业务事务提交后可靠补发，迁移必须把待投递事实与 Workspace、Alert 一起持久化。
 */
class RiskEmailNotificationOutboxMigrationTest {

    /**
     * 同一 Workspace 的同一 Alert 只能产生一条通知意图；否则重试或重复事件会造成重复邮件。
     */
    @Test
    void declaresWorkspaceScopedUniqueRiskEmailOutbox() throws IOException {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V38__add_risk_email_notification_outbox.sql");

        assertThat(resource.exists()).isTrue();
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE TABLE risk_email_notification_outbox")
                .contains("workspace_id BIGINT NOT NULL")
                .contains("alert_id BIGINT NOT NULL")
                .contains("UNIQUE (workspace_id, alert_id)")
                .contains("status VARCHAR(20) NOT NULL")
                .contains("next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL");
    }
}
