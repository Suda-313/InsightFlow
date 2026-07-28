package com.insightflow.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 不回归门禁测试：小幅模型波动可接受，但质量指标与失败数不能突破明确阈值。
 */
class EvaluationRegressionGateTest {

    /**
     * 候选版本在允许误差内时应通过；事实覆盖明显下降、编造率上升或失败题变多时必须拒绝。
     */
    @Test
    void rejectsQualityRegressionWhileAllowingSmallModelVariance() {
        EvaluationRegressionGate gate = new EvaluationRegressionGate();
        GoldEvaluationMetrics baseline = metrics(10, 10, 0, 0.80, 0.10, 0.80);
        GoldEvaluationMetrics tolerated = metrics(10, 10, 0, 0.79, 0.11, 0.79);
        GoldEvaluationMetrics regressed = metrics(10, 9, 1, 0.75, 0.13, 0.77);

        assertThat(gate.compare(baseline, tolerated).passed()).isTrue();
        assertThat(gate.compare(baseline, regressed).passed()).isFalse();
        assertThat(gate.compare(baseline, regressed).violations())
                .contains("fact_coverage_regressed", "forbidden_claim_rate_regressed", "refusal_compliance_regressed",
                        "answer_specificity_regressed", "failed_case_count_increased");
    }

    private GoldEvaluationMetrics metrics(
            int total, int succeeded, int failed, double coverage, double forbiddenHitRate, Double refusalComplianceRate) {
        return new GoldEvaluationMetrics(
                total, succeeded, failed, coverage, forbiddenHitRate, refusalComplianceRate,
                100L, 100L, 100L, 200L, 10L, 20L, 10L, 20L, 10L, 20L, coverage);
    }
}
