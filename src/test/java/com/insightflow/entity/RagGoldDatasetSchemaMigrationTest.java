package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 人工金标数据集迁移契约。
 *
 * <p>锁定四表结构、Workspace/Organization 隔离键、公开 UUID 证据列与唯一约束，
 * 防止后续把金标题目塞回 rag_evaluation_run 或 knowledge 表。</p>
 */
class RagGoldDatasetSchemaMigrationTest {

    @Test
    void declaresGoldDatasetTablesWithWorkspaceIsolation() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V27__add_rag_gold_dataset_schema.sql"));

        assertThat(sql).contains("CREATE TABLE rag_gold_dataset")
                .contains("workspace_id BIGINT NOT NULL REFERENCES workspace(id)")
                .contains("organization_id BIGINT NOT NULL REFERENCES organization(id)")
                .contains("dataset_key VARCHAR(80) NOT NULL")
                .contains("dataset_version VARCHAR(100) NOT NULL")
                .contains("split IN ('DEVELOPMENT', 'VALIDATION', 'FROZEN')")
                .contains("status IN ('DRAFT', 'PUBLISHED', 'FROZEN')")
                .contains("uq_rag_gold_dataset_version UNIQUE (workspace_id, dataset_key, dataset_version)")
                .contains("CREATE TABLE rag_gold_case")
                .contains("uq_rag_gold_case_key UNIQUE (dataset_id, case_key)")
                .contains("CREATE TABLE rag_gold_case_evidence")
                .contains("document_public_id UUID NOT NULL")
                .contains("version_public_id UUID")
                .contains("chunk_public_id UUID")
                .contains("CREATE TABLE rag_gold_case_assertion")
                .contains("assertion_type IN ('REQUIRED_FACT', 'FORBIDDEN_CLAIM')");
    }

    @Test
    void migrationResourceIsOnClasspath() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V27__add_rag_gold_dataset_schema.sql");
        assertThat(resource.exists()).isTrue();
        assertThat(resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("idx_rag_gold_dataset_workspace_status");
    }
}
