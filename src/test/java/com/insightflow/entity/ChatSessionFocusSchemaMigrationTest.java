package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** V33 迁移：chat_session 焦点列可空、类型正确。 */
class ChatSessionFocusSchemaMigrationTest {

    @Test
    void addsNullableFocusColumnsWithoutIndexes() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V33__add_chat_session_focus.sql"));

        assertThat(sql).contains("ADD COLUMN focus_topic_key VARCHAR(120)")
                .contains("ADD COLUMN focus_time_window VARCHAR(60)")
                .contains("ADD COLUMN focus_version_label VARCHAR(60)")
                .contains("ADD COLUMN focus_updated_at TIMESTAMPTZ")
                .doesNotContain("CREATE INDEX");
    }

    @Test
    void migrationResourceIsOnClasspath() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V33__add_chat_session_focus.sql");
        assertThat(resource.exists()).isTrue();
    }
}
