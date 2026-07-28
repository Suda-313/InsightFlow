package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * P3 知识治理与检索存储迁移契约。
 *
 * <p>该契约保证文档范围、不可覆盖版本和可检索切片在数据库层有独立表，避免继续把企业知识
 * 退化为只按 Workspace 保存的文本占位记录。</p>
 */
class KnowledgeRagSchemaMigrationTest {

    /**
     * 发布版本的向量和全文索引必须在同一迁移中声明，防止应用代码已依赖混合检索而部署漏装索引。
     */
    @Test
    void createsGovernedDocumentVersionAndPgvectorChunkTables() throws IOException {
        Path migration = Path.of("src", "main", "resources", "db", "migration",
                "V13__add_governed_knowledge_rag_schema.sql");

        String sql = Files.readString(migration);

        assertThat(sql).contains("CREATE EXTENSION IF NOT EXISTS vector")
                .contains("CREATE TABLE knowledge_document")
                .contains("organization_id BIGINT NOT NULL")
                .contains("target_workspace_id BIGINT")
                .contains("CREATE TABLE knowledge_document_version")
                .contains("UNIQUE (document_id, version_no)")
                .contains("CREATE TABLE knowledge_chunk")
                .contains("embedding vector(1024) NOT NULL")
                .contains("USING GIN (content_tsv)")
                .contains("USING ivfflat (embedding vector_cosine_ops)");
    }
}
