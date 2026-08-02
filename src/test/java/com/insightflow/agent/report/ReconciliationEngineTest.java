package com.insightflow.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.agent.dto.ReconciliationReport;
import com.insightflow.agent.dto.ReportDraft;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ReconciliationEngine 单元测试。
 */
class ReconciliationEngineTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    void extractsTicketCountFromSummaryAndMatchesActual() {
        ReportDraft draft = new ReportDraft(
                "本周共 100 条工单，主要问题为登录失败。",
                List.of(),
                List.of(),
                List.of());

        ReconciliationReport report = engine.reconcile(draft, 100, Map.of());

        assertThat(report.ok()).isTrue();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.checks()).anyMatch(c ->
                c.detail().contains("claimed=100") && c.passed());
        assertThat(report.overrides()).isEmpty();
    }

    @Test
    void flagsMismatchWhenSummaryCountDiffers() {
        ReportDraft draft = new ReportDraft(
                "本周共 99 条工单。",
                List.of(),
                List.of(),
                List.of());

        ReconciliationReport report = engine.reconcile(draft, 100, Map.of());

        assertThat(report.ok()).isFalse();
        assertThat(report.mismatches()).anyMatch(m -> m.contains("99") && m.contains("100"));
        assertThat(report.checks()).anyMatch(c -> !c.passed());
    }

    @Test
    void flagsAlertMentionsExceedingTicketCount() {
        ReportDraft draft = new ReportDraft(
                "本周共 100 条工单。",
                List.of(),
                List.of(),
                List.of(new ReportDraft.RiskAlert("high", "登录失败激增", "login_failure", "login_failure", 120)));

        ReconciliationReport report = engine.reconcile(draft, 100, Map.of());

        assertThat(report.ok()).isFalse();
        assertThat(report.mismatches()).anyMatch(m -> m.contains("120") && m.contains("100"));
    }

    @Test
    void alignsIssueMentionsWithActual() {
        ReportDraft draft = new ReportDraft(
                "本周共 100 条工单。",
                List.of(),
                List.of(),
                List.of(new ReportDraft.RiskAlert("high", "登录失败激增", "login_failure", "login_failure", 30)));

        ReconciliationReport report = engine.reconcile(draft, 100, Map.of("login_failure", 45));

        assertThat(report.ok()).isFalse();
        assertThat(report.overrides()).hasSize(1);
        assertThat(report.overrides().get(0).field()).contains("login_failure");
        assertThat(report.overrides().get(0).previousValue()).isEqualTo("30");
        assertThat(report.overrides().get(0).newValue()).isEqualTo("45");
    }

    @Test
    void returnsOkWhenEverythingMatches() {
        ReportDraft draft = new ReportDraft(
                "本周共 100 条工单。",
                List.of(),
                List.of(),
                List.of(new ReportDraft.RiskAlert("high", "登录失败激增", "login_failure", "login_failure", 45)));

        ReconciliationReport report = engine.reconcile(draft, 100, Map.of("login_failure", 45));

        assertThat(report.ok()).isTrue();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.overrides()).isEmpty();
    }

    @Test
    void handlesSummaryWithoutNumbers() {
        ReportDraft draft = new ReportDraft(
                "本周没有显著问题。",
                List.of(),
                List.of(),
                List.of());

        ReconciliationReport report = engine.reconcile(draft, 100, Map.of());

        assertThat(report.ok()).isFalse();
        assertThat(report.mismatches()).anyMatch(m -> m.contains("0") || m.contains("missing"));
    }

    @Test
    void supportsAlternativeNumberPattern() {
        ReportDraft draft = new ReportDraft(
                "100 条工单中，登录失败占比最高。",
                List.of(),
                List.of(),
                List.of());

        ReconciliationReport report = engine.reconcile(draft, 100, Map.of());

        assertThat(report.ok()).isTrue();
    }
}
