package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** V34 迁移：chat_session.rolling_summary 可空 TEXT。 */
class ChatSessionRollingSummaryMigrationTest {

    @Test
    void migrationAddsNullableRollingSummaryColumn() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V34__add_chat_session_rolling_summary.sql");
        String sql = new String(resource.getInputStream().readAllBytes());

        assertThat(sql).contains("rolling_summary");
        assertThat(sql).contains("TEXT");
    }
}
