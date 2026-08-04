package com.insightflow.agent.report;

import com.insightflow.report.OperationalReportRiskAssembler.ReportRisk;
import java.util.List;
import java.util.Map;

/**
 * 报告生成输入：聚合后的指标、告警、主题分布与 L2 表达分布等。
 */
public record MergedData(
        String summary,
        int actualTicketCount,
        Map<String, Integer> issueMentions,
        Map<String, Integer> expressionMentions,
        List<ReportRisk> risks) {

    /** 兼容尚未携带风险快照的调用方；正式报告任务会显式注入区间风险。 */
    public MergedData(
            String summary,
            int actualTicketCount,
            Map<String, Integer> issueMentions,
            Map<String, Integer> expressionMentions) {
        this(summary, actualTicketCount, issueMentions, expressionMentions, List.of());
    }
}
