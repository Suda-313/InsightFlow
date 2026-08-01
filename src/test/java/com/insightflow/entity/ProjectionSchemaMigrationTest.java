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

    /**
     * L2 标注表必须声明 Workspace 隔离、投影×事件唯一约束与固定 5 类枚举 CHECK，
     * 且必须携带 expression_rule_version / topic_pack_id / topic_pack_version 三个可追溯字段，
     * 否则历史趋势无法解释统计口径的变化（spec §6.1）。
     */
    @Test
    void declaresFeedbackProjectionAnnotationSchema() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V23__add_feedback_projection_annotation.sql");

        assertThat(resource.exists()).isTrue();
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE feedback_projection_annotation")
                .contains("workspace_id BIGINT NOT NULL REFERENCES workspace(id)")
                .contains("workspace_projection_id BIGINT NOT NULL REFERENCES workspace_projection(id)")
                .contains("feedback_event_id BIGINT NOT NULL REFERENCES feedback_event(id)")
                .contains("primary_expression IN ('expr_suggestion', 'expr_complaint', 'expr_praise', 'expr_neutral', 'expr_other')")
                .contains("expression_rule_version VARCHAR(80) NOT NULL")
                .contains("topic_pack_id VARCHAR(80) NOT NULL")
                .contains("topic_pack_version VARCHAR(80) NOT NULL")
                .contains("uq_feedback_projection_annotation_projection_event")
                .contains("CREATE TABLE expression_metric_bucket")
                .contains("uq_expression_metric_bucket");
    }

    /** V25 为 Workspace 增加可空 topic_pack_id，支持按 Workspace 绑定 L1 Pack。 */
    @Test
    void declaresWorkspaceTopicPackBinding() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V25__add_workspace_topic_pack_id.sql");

        assertThat(resource.exists()).isTrue();
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("ALTER TABLE workspace ADD COLUMN topic_pack_id VARCHAR(80) NULL");
    }

    /** V26 为 L2 标注行增加 Pack LLM Topic Skill 追溯列。 */
    @Test
    void declaresTopicLlmAnnotationMetadata() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V26__add_topic_llm_annotation_metadata.sql");

        assertThat(resource.exists()).isTrue();
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("topic_llm_prompt_version VARCHAR(80) NULL")
                .contains("topic_llm_confidence DOUBLE PRECISION NULL")
                .contains("ALTER TABLE feedback_projection_annotation");
    }

    /** 响应闭环的迁移必须保留响应状态、首位响应人与站内提醒，不能退化为派单或外部通知表。 */
    @Test
    void declaresMinimalInvestigationFollowUpSchema() throws IOException {
        ClassPathResource responseResource = new ClassPathResource("db/migration/V35__add_investigation_follow_up_state.sql");
        ClassPathResource reminderResource = new ClassPathResource("db/migration/V37__add_investigation_follow_up_reminder.sql");

        assertThat(responseResource.exists()).isTrue();
        assertThat(reminderResource.exists()).isTrue();
        String responseSql = new String(responseResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String reminderSql = new String(reminderResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(responseSql).contains("follow_up_status VARCHAR(30) NOT NULL DEFAULT 'awaiting_follow_up'")
                .contains("follow_up_by_user_public_id UUID")
                .contains("follow_up_started_at TIMESTAMP WITH TIME ZONE")
                .contains("idx_investigation_case_follow_up");
        assertThat(reminderSql).contains("follow_up_reminder_at TIMESTAMP WITH TIME ZONE")
                .contains("idx_investigation_case_follow_up_sla");
    }

    /** 风险队列必须保存创建时的分数、等级、依据与策略版本，保证后续策略变化不篡改历史排序。 */
    @Test
    void declaresFrozenRiskPrioritySnapshotSchema() throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/V36__add_risk_priority_snapshot.sql");

        assertThat(resource.exists()).isTrue();
        String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE risk_priority_snapshot")
                .contains("workspace_id BIGINT NOT NULL")
                .contains("alert_id BIGINT NOT NULL UNIQUE")
                .contains("score INTEGER NOT NULL")
                .contains("level VARCHAR(10) NOT NULL")
                .contains("reasons VARCHAR(1000) NOT NULL")
                .contains("policy_version VARCHAR(40) NOT NULL")
                .contains("idx_risk_priority_snapshot_workspace_score");
    }
}
