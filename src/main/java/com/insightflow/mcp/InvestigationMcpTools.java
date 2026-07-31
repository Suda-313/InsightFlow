package com.insightflow.mcp;

import com.insightflow.agent.investigation.InvestigationIntent;
import com.insightflow.agent.investigation.InvestigationPlan;
import com.insightflow.agent.investigation.InvestigationResult;
import com.insightflow.agent.investigation.InvestigationToolService;
import com.insightflow.agent.investigation.InvestigationToolType;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 将 P2 只读调查 Tool 暴露为 MCP 接口。
 *
 * <p>每个 Tool 必须提供 {@code workspacePublicId}，内部经 {@link InvestigationToolService} 解析 workspace；
 * 只返回聚合/脱敏结果，不包含写操作或内部主键。</p>
 */
@Component
@ConditionalOnProperty(name = "insightflow.mcp.enabled", havingValue = "true")
public class InvestigationMcpTools {

    private final InvestigationToolService investigationToolService;

    public InvestigationMcpTools(InvestigationToolService investigationToolService) {
        this.investigationToolService = investigationToolService;
    }

    @Tool(
            name = "insightflow_issue_trend",
            description = "只读：按 workspace 隔离查询单主题 14 日趋势聚合，返回脱敏证据文本。")
    public String issueTrend(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "用户问题，用于解析主题名") String question) {
        return runSingleTool(workspacePublicId, question, InvestigationIntent.TREND_EXPLANATION, InvestigationToolType.ISSUE_TREND);
    }

    @Tool(
            name = "insightflow_topic_distribution",
            description = "只读：按 workspace 隔离查询最近主题分布 TopN。")
    public String topicDistribution(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "用户问题上下文") String question) {
        return runSingleTool(workspacePublicId, question, InvestigationIntent.GENERAL_INQUIRY, InvestigationToolType.TOPIC_DISTRIBUTION);
    }

    @Tool(
            name = "insightflow_expression_distribution",
            description = "只读：按 workspace 隔离查询 L2 表达层分布。")
    public String expressionDistribution(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "用户问题上下文") String question) {
        return runSingleTool(
                workspacePublicId, question, InvestigationIntent.EXPRESSION_INQUIRY, InvestigationToolType.EXPRESSION_DISTRIBUTION);
    }

    @Tool(
            name = "insightflow_expression_topic_drilldown",
            description = "只读：按 workspace 隔离 L2→L1 议题钻取。")
    public String expressionTopicDrilldown(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "用户问题，含 L2 表达类目") String question) {
        return runSingleTool(
                workspacePublicId, question, InvestigationIntent.EXPRESSION_INQUIRY, InvestigationToolType.EXPRESSION_TOPIC_DRILLDOWN);
    }

    @Tool(
            name = "insightflow_expression_topic_samples",
            description = "只读：按 workspace 隔离读取 L2×L1 交叉脱敏样本。")
    public String expressionTopicSamples(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "用户问题，含表达与主题线索") String question) {
        return runSingleTool(
                workspacePublicId, question, InvestigationIntent.EXPRESSION_INQUIRY, InvestigationToolType.EXPRESSION_TOPIC_SAMPLES);
    }

    @Tool(
            name = "insightflow_alert_history",
            description = "只读：按 workspace 隔离查询告警历史与基线摘要。")
    public String alertHistory(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "用户问题，用于解析主题") String question) {
        return runSingleTool(
                workspacePublicId, question, InvestigationIntent.ANOMALY_INVESTIGATION, InvestigationToolType.ALERT_HISTORY);
    }

    @Tool(
            name = "insightflow_sample_feedback",
            description = "只读：按 workspace 隔离读取数量/长度受限的脱敏反馈样本。")
    public String sampleFeedback(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "用户问题，用于解析主题") String question) {
        return runSingleTool(
                workspacePublicId, question, InvestigationIntent.ANOMALY_INVESTIGATION, InvestigationToolType.SAMPLE_FEEDBACK);
    }

    @Tool(
            name = "insightflow_period_comparison",
            description = "只读：按 workspace 隔离比较最近 7 天与上一 7 天指标。")
    public String periodComparison(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "用户问题，用于解析主题") String question) {
        return runSingleTool(
                workspacePublicId, question, InvestigationIntent.PERIOD_COMPARISON, InvestigationToolType.PERIOD_COMPARISON);
    }

    @Tool(
            name = "insightflow_data_availability",
            description = "只读：说明版本/活动等外部事件数据是否可用，防止伪因果。")
    public String dataAvailability(
            @ToolParam(description = "Workspace 公开 UUID") String workspacePublicId,
            @ToolParam(description = "用户问题上下文") String question) {
        return runSingleTool(
                workspacePublicId, question, InvestigationIntent.VERSION_COMPARISON, InvestigationToolType.DATA_AVAILABILITY);
    }

    private String runSingleTool(
            String workspacePublicId,
            String question,
            InvestigationIntent intent,
            InvestigationToolType tool) {
        UUID workspaceId = UUID.fromString(workspacePublicId.trim());
        InvestigationResult result = investigationToolService.investigate(
                workspaceId, question, new InvestigationPlan(intent, List.of(tool)));
        return result.renderForPrompt();
    }
}
