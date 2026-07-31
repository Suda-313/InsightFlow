package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** V32 迁移：扩展弃权题型与多轮 context_turns 列。 */
class RagGoldAbstainSchemaMigrationTest {

    @Test
    void extendsQuestionTypeCheckAndAddsContextTurns() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V32__extend_rag_gold_abstain_and_context_turns.sql"));

        assertThat(sql).contains("DROP CONSTRAINT ck_rag_gold_case_question_type")
                .contains("'CHITCHAT', 'NO_ANSWER'")
                .contains("ADD COLUMN context_turns JSONB");
    }

    @Test
    void migrationResourceIsOnClasspath() throws IOException {
        ClassPathResource resource =
                new ClassPathResource("db/migration/V32__extend_rag_gold_abstain_and_context_turns.sql");
        assertThat(resource.exists()).isTrue();
    }
}
