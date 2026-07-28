package com.insightflow.agent.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 调查规划的回归测试。
 *
 * <p>规划器是纯规则组件，不调用模型或数据库；测试锁定高频中文问题与最少 Tool 集合，
 * 避免 Prompt 改动后无意扩大数据读取范围。</p>
 */
class InvestigationPlannerTest {

    private final InvestigationPlanner planner = new InvestigationPlanner(new InvestigationIntentDetector());

    /** “为什么暴增”必须优先走异常调查，读取趋势、告警和样本三类互补证据。 */
    @Test
    void plansAnomalyInvestigationWithTrendAlertAndSampleTools() {
        InvestigationPlan plan = planner.plan("玩法Bug 为什么暴增？");

        assertThat(plan.intent()).isEqualTo(InvestigationIntent.ANOMALY_INVESTIGATION);
        assertThat(plan.tools()).containsExactly(
                InvestigationToolType.ISSUE_TREND,
                InvestigationToolType.ALERT_HISTORY,
                InvestigationToolType.SAMPLE_FEEDBACK);
    }

    /** 环比问题只需要趋势与受控时间范围比较，不应额外读取样本文本或告警历史。 */
    @Test
    void plansPeriodComparisonWithMinimumTools() {
        InvestigationPlan plan = planner.plan("对比本周和上周的数据变化");

        assertThat(plan.intent()).isEqualTo(InvestigationIntent.PERIOD_COMPARISON);
        assertThat(plan.tools()).containsExactly(
                InvestigationToolType.ISSUE_TREND,
                InvestigationToolType.PERIOD_COMPARISON);
    }

    /** 版本前后问题应被识别为独立意图，后续由 Tool 明确报告是否具备版本来源数据。 */
    @Test
    void recognizesVersionComparisonInsteadOfPretendingCausality() {
        InvestigationPlan plan = planner.plan("7月版本更新前后登录失败有什么变化？");

        assertThat(plan.intent()).isEqualTo(InvestigationIntent.VERSION_COMPARISON);
        assertThat(plan.tools()).containsExactly(
                InvestigationToolType.ISSUE_TREND,
                InvestigationToolType.PERIOD_COMPARISON,
                InvestigationToolType.DATA_AVAILABILITY);
    }

    /** 周报问题需要总体主题、告警和趋势，不读取完整样本以控制上下文和敏感数据暴露。 */
    @Test
    void plansReportFromAggregateEvidenceOnly() {
        InvestigationPlan plan = planner.plan("生成一份运营周报");

        assertThat(plan.intent()).isEqualTo(InvestigationIntent.REPORT_GENERATION);
        assertThat(plan.tools()).containsExactly(
                InvestigationToolType.TOPIC_DISTRIBUTION,
                InvestigationToolType.ALERT_HISTORY,
                InvestigationToolType.ISSUE_TREND);
    }

    /** 未命中已知意图时退回主题分布，避免自由文本触发高成本或无关查询。 */
    @Test
    void defaultsToTopicDistributionForGeneralQuestion() {
        InvestigationPlan plan = planner.plan("现在有什么值得关注的吗？");

        assertThat(plan.intent()).isEqualTo(InvestigationIntent.GENERAL_INQUIRY);
        assertThat(plan.tools()).isEqualTo(List.of(InvestigationToolType.TOPIC_DISTRIBUTION));
    }
}
