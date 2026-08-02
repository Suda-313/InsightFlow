package com.insightflow.agent.report;

import com.insightflow.agent.dto.ReconciliationReport;
import com.insightflow.agent.dto.ReportDraft;

/**
 * 报告生成结果：最终草稿 + 对账报告。
 */
public record ReportResult(
        ReportDraft draft,
        ReconciliationReport reconciliation) {
}
