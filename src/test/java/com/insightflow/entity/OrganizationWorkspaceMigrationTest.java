package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 组织归属迁移契约。
 *
 * <p>P3 必须先把所有既有 Workspace 收敛到默认组织，才允许组织通用知识和 Workspace 专属知识
 * 在同一检索链路中安全共存。该测试只校验不可逆的 DDL 决策，不依赖本机 PostgreSQL 是否已安装 pgvector。</p>
 */
class OrganizationWorkspaceMigrationTest {

    /**
     * 防止后续只在 Java 实体增加字段而遗漏已有 Workspace 的回填和非空约束，导致运行时出现无组织归属的数据。
     */
    @Test
    void createsDefaultOrganizationAndMakesWorkspaceOrganizationMandatory() throws IOException {
        Path migration = Path.of("src", "main", "resources", "db", "migration",
                "V12__add_organization_and_workspace_ownership.sql");

        String sql = Files.readString(migration);

        assertThat(sql).contains("CREATE TABLE organization")
                .contains("is_default BOOLEAN NOT NULL")
                .contains("INSERT INTO organization")
                .contains("ALTER TABLE workspace ADD COLUMN organization_id")
                .contains("UPDATE workspace")
                .contains("ALTER COLUMN organization_id SET NOT NULL")
                .contains("FOREIGN KEY (organization_id) REFERENCES organization(id)");
    }
}
