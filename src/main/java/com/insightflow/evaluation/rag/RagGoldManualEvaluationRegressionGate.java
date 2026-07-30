package com.insightflow.evaluation.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 人工金标 RAG 评测的质量不回归门禁。
 *
 * <p>允许小幅模型波动，但关键检索与生成指标不能突破明确阈值；
 * 延迟与 Token 只记录比较，不作为阻断项。</p>
 */
@Component
public class RagGoldManualEvaluationRegressionGate {

    private static final double MAX_QUALITY_RATE_DECREASE = 0.02;
    private static final double MAX_FORBIDDEN_HIT_RATE_INCREASE = 0.01;

    /** 比较候选与基线扩展指标，返回违规码列表。 */
    public Comparison compare(RagGoldManualExtendedMetrics baseline, RagGoldManualExtendedMetrics candidate) {
        List<String> violations = new ArrayList<>();
        if (candidate.documentRecallAt8() < baseline.documentRecallAt8() - MAX_QUALITY_RATE_DECREASE) {
            violations.add("document_recall_at8_regressed");
        }
        if (candidate.chunkRecallAt8() < baseline.chunkRecallAt8() - MAX_QUALITY_RATE_DECREASE) {
            violations.add("chunk_recall_at8_regressed");
        }
        if (candidate.finalEvidenceCoverageAt8()
                < baseline.finalEvidenceCoverageAt8() - MAX_QUALITY_RATE_DECREASE) {
            violations.add("final_evidence_coverage_at8_regressed");
        }
        if (candidate.requiredFactCoverageRate() < baseline.requiredFactCoverageRate() - MAX_QUALITY_RATE_DECREASE) {
            violations.add("required_fact_coverage_regressed");
        }
        if (candidate.forbiddenClaimHitRate()
                > baseline.forbiddenClaimHitRate() + MAX_FORBIDDEN_HIT_RATE_INCREASE) {
            violations.add("forbidden_claim_rate_regressed");
        }
        if (isRefusalRegression(baseline.shouldRefuseComplianceRate(), candidate.shouldRefuseComplianceRate())) {
            violations.add("should_refuse_compliance_regressed");
        }
        if (candidate.citationSupportRate() < baseline.citationSupportRate() - MAX_QUALITY_RATE_DECREASE) {
            violations.add("citation_support_rate_regressed");
        }
        if (candidate.failedCaseCount() > baseline.failedCaseCount()) {
            violations.add("failed_case_count_increased");
        }
        return new Comparison(violations.isEmpty(), List.copyOf(violations));
    }

    private boolean isRefusalRegression(Double baseline, Double candidate) {
        return baseline != null && candidate != null && candidate < baseline - MAX_QUALITY_RATE_DECREASE;
    }

    /** 质量门禁结果。 */
    public record Comparison(boolean passed, List<String> violations) {
    }
}
