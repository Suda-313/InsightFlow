package com.insightflow.agent.report;

import java.util.Map;

/**
 * 报告生成输入：聚合后的指标、告警、主题分布等。
 */
public record MergedData(
        String summary,
        int actualTicketCount,
        Map<String, Integer> issueMentions) {
}
