package com.insightflow.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.investigation.window.InvestigationWindowType;
import org.junit.jupiter.api.Test;

/** 锁定 Worker 只读取已冻结窗口，绝不为缺失计划推导当前时间范围。 */
class FrozenInvestigationPlanReaderTest {

    @Test
    void readsFrozenIsoWindow() {
        InvestigationCase investigation = InvestigationCase.queued(7L, 9L);
        investigation.freezePlan("""
                {"windows":[{"type":"WEEKLY","anchorTime":"2026-08-08T00:00Z","currentStart":"2026-08-01T00:00Z","currentEnd":"2026-08-08T00:00Z","previousStart":"2026-07-25T00:00Z","previousEnd":"2026-08-01T00:00Z"}]}
                """);

        assertThat(new FrozenInvestigationPlanReader(new ObjectMapper()).readWindows(investigation))
                .singleElement()
                .satisfies(window -> {
                    assertThat(window.type()).isEqualTo(InvestigationWindowType.WEEKLY);
                    assertThat(window.currentStart().toString()).isEqualTo("2026-08-01T00:00Z");
                });
    }

    @Test
    void rejectsMissingPlanInsteadOfReplanningAtWorkerTime() {
        assertThatThrownBy(() -> new FrozenInvestigationPlanReader(new ObjectMapper())
                .readWindows(InvestigationCase.queued(7L, 9L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("计划缺失");
    }
}
