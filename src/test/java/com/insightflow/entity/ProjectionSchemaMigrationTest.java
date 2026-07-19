package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 守住 V6 的最小结构契约；真实 Flyway 执行在本阶段末通过本地 PostgreSQL 验证。
 */
class ProjectionSchemaMigrationTest {

    /**
     * 自动投影、看板事实与只读报告必须在同一前向迁移中完整落库。
     */
    @Test
    void declaresProjectionDashboardAndReportSchema() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V6__add_dashboard_projection_schema.sql");

        assertThat(resource.exists()).isTrue();
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("ADD COLUMN projection_status")
                .contains("CREATE TABLE workspace_projection")
                .contains("CREATE TABLE issue_catalog")
                .contains("CREATE TABLE issue_metric_bucket")
                .contains("CREATE TABLE alert")
                .contains("CREATE TABLE analysis_report");
    }
}
