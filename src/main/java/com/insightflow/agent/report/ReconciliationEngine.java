package com.insightflow.agent.report;

import com.insightflow.agent.dto.ReconciliationReport;
import com.insightflow.agent.dto.ReportDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 纯代码对账引擎：对比 LLM 草稿中的数字与确定性数据。
 */
@Component
public class ReconciliationEngine {

    private static final Pattern TICKET_COUNT_PATTERN = Pattern.compile("(共\\s*(\\d+)\\s*条|\\d+)\\s*条工单");

    /**
     * 对账：检查草稿中的数字是否与确定性数据一致。
     *
     * @param draft             LLM 生成的报告草稿
     * @param actualTicketCount 实际工单总数
     * @param issueMentions     各 issue 的实际提及次数
     * @return 对账报告
     */
    public ReconciliationReport reconcile(ReportDraft draft, int actualTicketCount,
                                          Map<String, Integer> issueMentions) {
        List<String> mismatches = new ArrayList<>();
        List<ReconciliationReport.Check> checks = new ArrayList<>();
        List<ReconciliationReport.Override> overrides = new ArrayList<>();

        int claimedCount = extractTicketCount(draft.executiveSummary());
        boolean summaryOk = claimedCount == actualTicketCount;
        if (!summaryOk) {
            mismatches.add("executiveSummary claimed " + claimedCount + " tickets, actual " + actualTicketCount);
        }
        checks.add(new ReconciliationReport.Check(
                "executiveSummary ticket count",
                summaryOk,
                "claimed=" + claimedCount + ", actual=" + actualTicketCount));

        boolean alertsOk = true;
        for (ReportDraft.RiskAlert alert : draft.riskAlerts()) {
            boolean alertOk = alert.mentions() <= actualTicketCount;
            if (!alertOk) {
                alertsOk = false;
                mismatches.add("alert " + alert.issue() + " mentions " + alert.mentions()
                        + " exceeds actual ticket count " + actualTicketCount);
            }
            checks.add(new ReconciliationReport.Check(
                    "alert " + alert.issue() + " mentions upper bound",
                    alertOk,
                    "mentions=" + alert.mentions() + ", actual=" + actualTicketCount));
        }

        for (ReportDraft.RiskAlert alert : draft.riskAlerts()) {
            Integer actualMentions = issueMentions.get(alert.issue());
            if (actualMentions != null && alert.mentions() != actualMentions) {
                overrides.add(new ReconciliationReport.Override(
                        "riskAlerts." + alert.issue() + ".mentions",
                        String.valueOf(alert.mentions()),
                        String.valueOf(actualMentions),
                        "align with actual issue mentions"));
            }
        }

        boolean ok = summaryOk && alertsOk && overrides.isEmpty();
        return new ReconciliationReport(ok, mismatches, checks, overrides);
    }

    /**
     * 从摘要文本中提取第一个出现的"X 条"或"共 X 条"数字。
     * 如果无法提取则返回 0。
     */
    private int extractTicketCount(String summary) {
        if (summary == null || summary.isBlank()) {
            return 0;
        }
        Matcher matcher = TICKET_COUNT_PATTERN.matcher(summary);
        if (matcher.find()) {
            String group = matcher.group(2);
            if (group == null) {
                group = matcher.group(1);
            }
            try {
                return Integer.parseInt(group.replaceAll("\\s", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
