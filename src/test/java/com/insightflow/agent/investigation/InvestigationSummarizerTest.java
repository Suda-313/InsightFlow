package com.insightflow.agent.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 调查摘要层的确定性渲染测试。
 *
 * <p>验证摘要只从 Tool 证据正文解析数值，不推断缺失字段，且同一输入多次调用结果一致。</p>
 */
class InvestigationSummarizerTest {

    private final InvestigationSummarizer summarizer = new InvestigationSummarizer();

    /** 趋势证据可解析时，关键变化必须包含方向、幅度与来源证据 id。 */
    @Test
    void includesDirectionMagnitudeAndEvidenceIdForTrendEvidence() {
        InvestigationResult result = resultWithEvidence(
                trendEvidence(
                        "trend:bug_gameplay:last_14_days",
                        "来源 issue_metric_bucket；玩法Bug 最近7天 30 条，前7天 12 条。",
                        true),
                alertEvidence());

        String summary = summarizer.summarize(result);

        assertThat(summary)
                .contains("## 调查摘要")
                .contains("覆盖范围：玩法Bug / 近14天")
                .contains("关键变化：玩法Bug 上升+18条（[trend:bug_gameplay:last_14_days]）")
                .contains("数据不足项：无")
                .contains("证据条数：2");
    }

    /** 比较证据使用正文中的绝对变化字段，而非自行推算。 */
    @Test
    void usesExplicitAbsoluteDeltaFromPeriodComparison() {
        InvestigationResult result = resultWithEvidence(
                comparisonEvidence(
                        "comparison:bug_login:last_14_days",
                        "来源 issue_metric_bucket；登录失败 最近7天 20 条，前7天 35 条，绝对变化 -15 条。",
                        true));

        String summary = summarizer.summarize(result);

        assertThat(summary).contains("关键变化：登录失败 下降-15条（[comparison:bug_login:last_14_days]）");
    }

    /** 数值字段无法解析时，不输出「关键变化」行，避免模型看到推断性结论。 */
    @Test
    void omitsKeyChangeLineWhenNumbersAreNotParseable() {
        InvestigationResult result = resultWithEvidence(
                new InvestigationEvidence(
                        "trend:bug_gameplay:last_14_days",
                        InvestigationToolType.ISSUE_TREND,
                        "主题趋势",
                        "未识别具体主题，无法查询单主题趋势。",
                        false),
                alertEvidence());

        String summary = summarizer.summarize(result);

        assertThat(summary).doesNotContain("关键变化");
        assertThat(summary).contains("数据不足项：主题趋势");
    }

    /** sufficient=false 的证据标题必须出现在「数据不足项」。 */
    @Test
    void listsInsufficientEvidenceTitles() {
        InvestigationResult result = resultWithEvidence(
                new InvestigationEvidence(
                        "availability:version_event",
                        InvestigationToolType.DATA_AVAILABILITY,
                        "版本数据可用性",
                        "当前未接入版本或活动事件数据，不能确认版本前后变化，更不能据此判断因果。",
                        false),
                trendEvidence(
                        "trend:bug_gameplay:last_14_days",
                        "来源 issue_metric_bucket；玩法Bug 最近7天 10 条，前7天 10 条。",
                        true));

        String summary = summarizer.summarize(result);

        assertThat(summary).contains("数据不足项：版本数据可用性");
        assertThat(summary).contains("关键变化：玩法Bug 持平0条（[trend:bug_gameplay:last_14_days]）");
    }

    /** 同一输入两次调用必须完全一致，保证 Prompt 可复现。 */
    @Test
    void producesIdenticalOutputForSameInput() {
        InvestigationResult result = resultWithEvidence(
                trendEvidence(
                        "trend:bug_gameplay:last_14_days",
                        "来源 issue_metric_bucket；玩法Bug 最近7天 30 条，前7天 12 条。",
                        true),
                comparisonEvidence(
                        "comparison:bug_gameplay:last_14_days",
                        "来源 issue_metric_bucket；玩法Bug 最近7天 30 条，前7天 12 条，绝对变化 +18 条。",
                        true));

        String first = summarizer.summarize(result);
        String second = summarizer.summarize(result);

        assertThat(first).isEqualTo(second);
    }

    /** renderForPrompt 在调查计划与证据索引之间插入摘要段。 */
    @Test
    void renderForPromptInsertsSummaryBeforeEvidenceIndex() {
        InvestigationResult result = resultWithEvidence(trendEvidence(
                "trend:bug_gameplay:last_14_days",
                "来源 issue_metric_bucket；玩法Bug 最近7天 30 条，前7天 12 条。",
                true));

        String prompt = result.renderForPrompt();

        int planIndex = prompt.indexOf("## 调查计划");
        int summaryIndex = prompt.indexOf("## 调查摘要");
        int evidenceIndex = prompt.indexOf("## 证据索引");

        assertThat(planIndex).isLessThan(summaryIndex);
        assertThat(summaryIndex).isLessThan(evidenceIndex);
        assertThat(prompt).contains("关键变化：玩法Bug 上升+18条（[trend:bug_gameplay:last_14_days]）");
    }

    private InvestigationResult resultWithEvidence(InvestigationEvidence... evidence) {
        InvestigationPlan plan = new InvestigationPlan(
                InvestigationIntent.ANOMALY_INVESTIGATION,
                List.of(InvestigationToolType.ISSUE_TREND, InvestigationToolType.ALERT_HISTORY));
        return new InvestigationResult(plan, List.of(evidence));
    }

    private InvestigationEvidence trendEvidence(String id, String content, boolean sufficient) {
        return new InvestigationEvidence(id, InvestigationToolType.ISSUE_TREND, "主题趋势", content, sufficient);
    }

    private InvestigationEvidence comparisonEvidence(String id, String content, boolean sufficient) {
        return new InvestigationEvidence(
                id, InvestigationToolType.PERIOD_COMPARISON, "时间范围比较", content, sufficient);
    }

    private InvestigationEvidence alertEvidence() {
        return new InvestigationEvidence(
                "alerts:recent",
                InvestigationToolType.ALERT_HISTORY,
                "告警与基线",
                "来源 alert；玩法Bug 当前值 30、EWMA 12.0、z-score 3.0、状态 ACTIVE。",
                true);
    }
}
