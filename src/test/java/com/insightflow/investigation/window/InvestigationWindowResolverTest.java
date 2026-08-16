package com.insightflow.investigation.window;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证调查窗口始终围绕不可变告警锚点计算，不能受 Worker 实际运行时间影响。 */
class InvestigationWindowResolverTest {

    /** 该断言会在 Resolver 错把当前时间当锚点、或把窗口右端点算入下一窗口时失败。 */
    @Test
    void resolvesWeeklyWindowAroundAlertAnchorUsingNonOverlappingHalfOpenRanges() {
        InvestigationWindowResolver resolver = new InvestigationWindowResolver();
        OffsetDateTime anchor = OffsetDateTime.parse("2026-08-08T00:00:00Z");

        List<InvestigationWindow> windows = resolver.resolve(anchor, InvestigationWindowSelection.WEEKLY);

        assertThat(windows).containsExactly(new InvestigationWindow(
                InvestigationWindowType.WEEKLY,
                anchor,
                OffsetDateTime.parse("2026-08-02T00:00:00Z"),
                OffsetDateTime.parse("2026-08-09T00:00:00Z"),
                OffsetDateTime.parse("2026-07-26T00:00:00Z"),
                OffsetDateTime.parse("2026-08-02T00:00:00Z")));
    }

    /** BOTH 必须展开为固定顺序的两套独立窗口，避免不同窗口的证据互相覆盖。 */
    @Test
    void resolvesBothAsShortTermThenWeeklyWindows() {
        InvestigationWindowResolver resolver = new InvestigationWindowResolver();
        OffsetDateTime anchor = OffsetDateTime.parse("2026-08-08T12:00:00Z");

        List<InvestigationWindow> windows = resolver.resolve(anchor, InvestigationWindowSelection.BOTH);

        assertThat(windows).extracting(InvestigationWindow::type)
                .containsExactly(InvestigationWindowType.SHORT_TERM, InvestigationWindowType.WEEKLY);
        assertThat(windows.get(0).currentStart()).isEqualTo(OffsetDateTime.parse("2026-08-08T12:00:00Z"));
        assertThat(windows.get(0).currentEnd()).isEqualTo(OffsetDateTime.parse("2026-08-09T12:00:00Z"));
        assertThat(windows.get(0).previousEnd()).isEqualTo(OffsetDateTime.parse("2026-08-08T12:00:00Z"));
    }
}
