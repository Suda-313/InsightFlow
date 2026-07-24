package com.insightflow.evaluation;

import java.util.ArrayList;
import java.util.List;

/**
 * 金标评测的质量不回归门禁。
 *
 * <p>模型输出存在天然随机性，因此质量比例允许最多两个百分点波动；禁止编造命中率只允许一个百分点上升。
 * 延迟和 Token 成本在结果中比较但不作为质量发布阻断项，避免以牺牲事实正确性换取更低成本。</p>
 */
public class EvaluationRegressionGate {

    /** 覆盖率和拒答合规率允许的最大下降幅度。 */
    private static final double MAX_QUALITY_RATE_DECREASE = 0.02;

    /** 禁止断言命中率允许的最大上升幅度，越低越好。 */
    private static final double MAX_FORBIDDEN_HIT_RATE_INCREASE = 0.01;

    /**
     * 比较候选与基线的质量指标，返回机器可读的违规码而不抛出异常，便于 API 展示全部退化原因。
     */
    public Comparison compare(GoldEvaluationMetrics baseline, GoldEvaluationMetrics candidate) {
        List<String> violations = new ArrayList<>();
        if (candidate.factCoverageRate() < baseline.factCoverageRate() - MAX_QUALITY_RATE_DECREASE) {
            violations.add("fact_coverage_regressed");
        }
        if (candidate.forbiddenClaimHitRate()
                > baseline.forbiddenClaimHitRate() + MAX_FORBIDDEN_HIT_RATE_INCREASE) {
            violations.add("forbidden_claim_rate_regressed");
        }
        if (isRefusalRegression(baseline.refusalComplianceRate(), candidate.refusalComplianceRate())) {
            violations.add("refusal_compliance_regressed");
        }
        if (candidate.answerSpecificityRate()
                < baseline.answerSpecificityRate() - MAX_QUALITY_RATE_DECREASE) {
            violations.add("answer_specificity_regressed");
        }
        if (candidate.failedCaseCount() > baseline.failedCaseCount()) {
            violations.add("failed_case_count_increased");
        }
        return new Comparison(violations.isEmpty(), List.copyOf(violations));
    }

    /** 只有基线和候选都包含拒答题时才比较，避免数据集差异造成伪退化。 */
    private boolean isRefusalRegression(Double baseline, Double candidate) {
        return baseline != null && candidate != null && candidate < baseline - MAX_QUALITY_RATE_DECREASE;
    }

    /** 质量门禁结果；通过不代表成本最优，成本应由同批指标另行评估。 */
    public record Comparison(boolean passed, List<String> violations) {
    }
}
