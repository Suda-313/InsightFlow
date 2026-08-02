package com.insightflow.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.Alert;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 风险优先级的业务契约：排序必须只依赖已冻结的告警事实和受控主题权重，
 * 不能由模型自由决定，从而保证运营人员能够复核“为什么这件事排在前面”。
 */
class RiskPriorityServiceTest {

    /**
     * 高偏离、高规模且属于高风险主题的异常必须成为 P0，
     * 避免资产或登录类问题被大量普通反馈淹没。
     */
    @Test
    void assignsP0ForLargeHighRiskSurge() {
        Alert alert = Alert.active(7L, 8L, 9L, OffsetDateTime.parse("2026-07-31T08:00:00Z"),
                100, 10.0, 2.0, 8.0, 14, "{}");

        RiskPriority priority = new RiskPriorityService().score(alert, 20, 4);

        assertThat(priority.level()).isEqualTo(RiskLevel.P0);
        assertThat(priority.score()).isGreaterThanOrEqualTo(80);
        assertThat(priority.reasons()).contains("异常强度高", "影响规模大", "高风险主题");
    }

    /**
     * 低规模的一般体验波动仍须留在低优先级，不能仅因达到告警阈值就打断运营工作。
     */
    @Test
    void assignsP3ForSmallLowRiskAlert() {
        Alert alert = Alert.active(7L, 8L, 9L, OffsetDateTime.parse("2026-07-31T08:00:00Z"),
                5, 2.0, 1.0, 3.0, 5, "{}");

        RiskPriority priority = new RiskPriorityService().score(alert, 5, 0);

        assertThat(priority.level()).isEqualTo(RiskLevel.P3);
        assertThat(priority.score()).isLessThan(40);
        assertThat(priority.reasons()).contains("影响规模小", "一般风险主题");
    }
}
