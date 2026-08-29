package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** V39 迁移只添加可空处理游标，不把它误建成 chat_message 的领域外键。 */
class ChatSessionSummaryCursorMigrationTest {

    @Test
    void migrationAddsNullableSummaryCursorWithoutForeignKey() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V39__add_chat_session_summary_cursor.sql");
        String sql = new String(resource.getInputStream().readAllBytes());

        assertThat(sql).contains("summary_until_message_id").contains("BIGINT").doesNotContain("REFERENCES chat_message");
    }
}
