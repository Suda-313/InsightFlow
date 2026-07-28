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

    /**
     * 会话迁移必须同时声明工作区隔离、公共标识和消息角色约束，不能只创建无归属的文本表。
     */
    @Test
    void declaresWorkspaceScopedChatSessionSchema() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V9__add_chat_session_schema.sql");

        assertThat(resource.exists()).isTrue();
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE chat_session")
                .contains("CREATE TABLE chat_message")
                .contains("workspace_id BIGINT NOT NULL")
                .contains("public_id UUID NOT NULL UNIQUE")
                .contains("role IN ('user', 'assistant')")
                .contains("idx_chat_message_workspace_session_created");
    }

    /**
     * AgentRun 迁移必须约束运行状态、保留工作区隔离键和最近记录索引，不能退化为无边界日志表。
     */
    @Test
    void declaresWorkspaceScopedAgentRunSchema() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V10__add_agent_run_schema.sql");

        assertThat(resource.exists()).isTrue();
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE agent_run")
                .contains("public_id UUID NOT NULL UNIQUE")
                .contains("workspace_id BIGINT NOT NULL")
                .contains("status IN ('running', 'succeeded', 'failed')")
                .contains("input_summary TEXT NOT NULL")
                .contains("idx_agent_run_workspace_created");
    }
}
