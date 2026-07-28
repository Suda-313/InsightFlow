package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * RAG 专项评测历史迁移契约。
 *
 * <p>专项 RAG 指标的结构与通用 Prompt 金标评测不同，因此必须使用独立表保存。该测试锁定表、
 * 工作区隔离键和按工作区倒序读取索引，防止后续修改时把两类指标混存在同一 JSON 契约中。</p>
 */
class RagEvaluationSchemaMigrationTest {

    /**
     * 评测历史必须归属到 Workspace，且版本信息、指标和逐题规则结果都需要持久化，才能支持回归比较。
     */
    @Test
    void createsWorkspaceScopedRagEvaluationHistoryTable() throws IOException {
        Path migration = Path.of("src", "main", "resources", "db", "migration",
                "V14__add_rag_evaluation_run_schema.sql");

        String sql = Files.readString(migration);

        assertThat(sql).contains("CREATE TABLE rag_evaluation_run")
                .contains("workspace_id BIGINT NOT NULL REFERENCES workspace(id)")
                .contains("dataset_version VARCHAR(100) NOT NULL")
                .contains("prompt_version VARCHAR(100) NOT NULL")
                .contains("model_name VARCHAR(120) NOT NULL")
                .contains("retrieval_version VARCHAR(100) NOT NULL")
                .contains("metrics_json JSONB NOT NULL")
                .contains("case_results_json JSONB NOT NULL")
                .contains("CREATE INDEX idx_rag_evaluation_run_workspace_created")
                .contains("(workspace_id, created_at DESC)");
    }
}
