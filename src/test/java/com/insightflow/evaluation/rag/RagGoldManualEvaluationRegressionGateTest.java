package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 人工金标 RAG 质量门禁：允许小幅波动，阻断明显退化。
 */
class RagGoldManualEvaluationRegressionGateTest {

    @Test
    void rejectsQualityRegressionAgainstBaseline() {
        RagGoldManualEvaluationRegressionGate gate = new RagGoldManualEvaluationRegressionGate();
        RagGoldManualExtendedMetrics baseline = metrics(0.80, 0.75, 0.50, 0.90, 0.05, 0.95, 0);
        RagGoldManualExtendedMetrics tolerated = metrics(0.79, 0.74, 0.49, 0.89, 0.06, 0.94, 0);
        RagGoldManualExtendedMetrics regressed = metrics(0.70, 0.60, 0.30, 0.80, 0.15, 0.80, 2);

        assertThat(gate.compare(baseline, tolerated).passed()).isTrue();
        assertThat(gate.compare(baseline, regressed).passed()).isFalse();
        assertThat(gate.compare(baseline, regressed).violations())
                .contains(
                        "document_recall_at8_regressed",
                        "chunk_recall_at8_regressed",
                        "final_evidence_coverage_at8_regressed",
                        "required_fact_coverage_regressed",
                        "forbidden_claim_rate_regressed",
                        "citation_support_rate_regressed",
                        "failed_case_count_increased");
    }

    @Test
    void rejectsFinalEvidenceCoverageRegressionWhenChunkRecallStillPasses() {
        RagGoldManualEvaluationRegressionGate gate = new RagGoldManualEvaluationRegressionGate();
        RagGoldManualExtendedMetrics baseline = metrics(0.80, 0.75, 0.50, 0.90, 0.05, 0.95, 0);
        RagGoldManualExtendedMetrics partialOnly = metrics(0.80, 0.75, 0.40, 0.90, 0.05, 0.95, 0);

        assertThat(gate.compare(baseline, partialOnly).passed()).isFalse();
        assertThat(gate.compare(baseline, partialOnly).violations())
                .containsExactly("final_evidence_coverage_at8_regressed");
    }

    private RagGoldManualExtendedMetrics metrics(
            double docRecall8,
            double chunkRecall8,
            double finalEvidenceCoverageAt8,
            double factCoverage,
            double forbiddenHit,
            double citationSupport,
            int failedCases) {
        return new RagGoldManualExtendedMetrics(
                "ops-rag-v1", "dev-240", "DEVELOPMENT", "checksum", List.of("case-1"),
                docRecall8, docRecall8, docRecall8,
                chunkRecall8, chunkRecall8, chunkRecall8,
                docRecall8, docRecall8,
                factCoverage, forbiddenHit, citationSupport, 1.0,
                10L, 20L, 30L, 40L,
                1,
                "unavailable", "unavailable", "unavailable",
                Map.of(), 5 - failedCases, failedCases,
                "prompt-v1", "text-embedding-v3", "knowledge:rrf:v1", null, "end-to-end",
                finalEvidenceCoverageAt8, finalEvidenceCoverageAt8, finalEvidenceCoverageAt8, 0.0, 0, 0, 0, 0.0, null, null, chunkRecall8, "single_any_evidence;cross_version_requirement_group");
    }
}
