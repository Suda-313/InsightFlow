package com.insightflow.service.analysis;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 删除某次投影产生的分析事实，供「半完成投影」重跑时使用。
 *
 * <p>不删 {@code feedback_event} 与 {@code import_file}——CSV 导入结果保留，只清看板/L1/L2 聚合层，
 * 以便用最新 Pack 规则与 L2 标注管线完整重投影。</p>
 */
@Component
public class ProjectionFactWiper {

    private final JdbcTemplate jdbcTemplate;

    public ProjectionFactWiper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按 Workspace 清除投影事实；{@code projectionId} 仅用于日志语义，删除范围覆盖该工作区全部投影产物。
     */
    public void wipeWorkspaceAnalysisFacts(Long workspaceId, Long projectionId) {
        jdbcTemplate.update("DELETE FROM action_execution WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM action_proposal WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM investigation_evidence_snapshot WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM investigation_case WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM feedback_review_candidate WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM feedback_projection_annotation WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM expression_metric_bucket WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM feedback_issue_link WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM cell_issue WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM issue_metric_bucket WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM issue_baseline_profile WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM alert WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM data_cell WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM analysis_report_file WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM analysis_report WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM issue_alias WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM issue_catalog WHERE workspace_id = ?", workspaceId);
        // projection 记录本身由完成服务管理；此处只清事实表，避免半完成状态阻塞 L2 回填。
    }
}
