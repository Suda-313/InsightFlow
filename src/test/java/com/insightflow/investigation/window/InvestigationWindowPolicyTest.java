package com.insightflow.investigation.window;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.Alert;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** 验证第一版在 classification 没有强类型持久化契约时使用稳定的周窗口默认值。 */
class InvestigationWindowPolicyTest {

    /** 此测试防止策略偷偷解析临时 classification 或退回 now() 驱动的窗口选择。 */
    @Test
    void defaultsToWeeklyWithoutDependingOnTransientClassification() {
        Alert alert = Alert.active(7L, 3L, 5L, OffsetDateTime.parse("2026-08-08T00:00:00Z"),
                10, 2, 1, 3, 5, "{\"classification\":\"surge\"}");

        assertThat(new InvestigationWindowPolicy().defaultFor(alert)).isEqualTo(InvestigationWindowSelection.WEEKLY);
    }
}
