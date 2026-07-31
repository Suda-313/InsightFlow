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

    @Test
    void rejectsComparisonWhenDatasetChecksumDiffers() {
        RagGoldManualEvaluationRegressionGate gate = new RagGoldManualEvaluationRegressionGate();
        RagGoldManualExtendedMetrics baseline = metrics(0.80, 0.75, 0.50, 0.90, 0.05, 0.95, 0, "checksum-a");
        RagGoldManualExtendedMetrics candidate = metrics(0.80, 0.75, 0.50, 0.90, 0.05, 0.95, 0, "checksum-b");

        assertThat(gate.compare(baseline, candidate).passed()).isFalse();
        assertThat(gate.compare(baseline, candidate).violations())
                .containsExactly("dataset_checksum_mismatch");
    }

    @Test
    void allowsComparisonWhenDatasetChecksumMatches() {
        RagGoldManualEvaluationRegressionGate gate = new RagGoldManualEvaluationRegressionGate();
        RagGoldManualExtendedMetrics baseline = metrics(0.80, 0.75, 0.50, 0.90, 0.05, 0.95, 0, "same-checksum");
        RagGoldManualExtendedMetrics candidate = metrics(0.80, 0.75, 0.50, 0.90, 0.05, 0.95, 0, "same-checksum");

        assertThat(gate.compare(baseline, candidate).passed()).isTrue();
    }

    @Test
    void rejectsFalseAbstentionRateRegression() {
        RagGoldManualEvaluationRegressionGate gate = new RagGoldManualEvaluationRegressionGate();
        RagGoldManualExtendedMetrics baseline = metricsWithAbstention(0.0, null);
        RagGoldManualExtendedMetrics tolerated = metricsWithAbstention(0.01, null);
        RagGoldManualExtendedMetrics regressed = metricsWithAbstention(0.05, null);

        assertThat(gate.compare(baseline, tolerated).passed()).isTrue();
        assertThat(gate.compare(baseline, regressed).passed()).isFalse();
        assertThat(gate.compare(baseline, regressed).violations())
                .contains("false_abstention_rate_regressed");
    }

    private RagGoldManualExtendedMetrics metricsWithAbstention(
            Double falseAbstentionRate, Double correctAbstentionRate) {
        RagGoldManualExtendedMetrics base = metrics(0.80, 0.75, 0.50, 0.90, 0.05, 0.95, 0);
        return new RagGoldManualExtendedMetrics(
                base.datasetKey(),
                base.datasetVersionLabel(),
                base.split(),
                base.checksum(),
                base.caseKeys(),
                base.documentRecallAt1(),
                base.documentRecallAt3(),
                base.documentRecallAt8(),
                base.chunkRecallAt1(),
                base.chunkRecallAt3(),
                base.chunkRecallAt8(),
                base.mrr(),
                base.ndcgAt8(),
                base.requiredFactCoverageRate(),
                base.forbiddenClaimHitRate(),
                base.citationSupportRate(),
                base.shouldRefuseComplianceRate(),
                falseAbstentionRate,
                correctAbstentionRate,
                base.retrievalP50Ms(),
                base.retrievalP95Ms(),
                base.generationP50Ms(),
                base.generationP95Ms(),
                base.latencySampleCount(),
                base.promptTokens(),
                base.completionTokens(),
                base.totalTokens(),
                base.byQuestionType(),
                base.succeededCaseCount(),
                base.failedCaseCount(),
                base.promptVersion(),
                base.embeddingModel(),
                base.retrievalConfigVersion(),
                base.retrievalFunnel(),
                base.evaluationMode(),
                base.finalEvidenceCoverageAt8(),
                base.requirementGroupCoverageAt8(),
                base.primaryRecallAt8(),
                base.finalCrossDocumentDualHitAt8(),
                base.rerankGainedCaseCount(),
                base.rerankLostCaseCount(),
                base.rerankDemotedCaseCount(),
                base.rerankFallbackRate(),
                base.rerankLatencyP50Ms(),
                base.rerankLatencyP95Ms(),
                base.chunkRecallAt8AnyEvidence(),
                base.chunkRecallMetricMode());
    }

    private RagGoldManualExtendedMetrics metrics(
            double docRecall8,
            double chunkRecall8,
            double finalEvidenceCoverageAt8,
            double factCoverage,
            double forbiddenHit,
            double citationSupport,
            int failedCases) {
        return metrics(
                docRecall8,
                chunkRecall8,
                finalEvidenceCoverageAt8,
                factCoverage,
                forbiddenHit,
                citationSupport,
                failedCases,
                "checksum");
    }

    private RagGoldManualExtendedMetrics metrics(
            double docRecall8,
            double chunkRecall8,
            double finalEvidenceCoverageAt8,
            double factCoverage,
            double forbiddenHit,
            double citationSupport,
            int failedCases,
            String checksum) {
        return new RagGoldManualExtendedMetrics(
                "ops-rag-v1", "dev-240", "DEVELOPMENT", checksum, List.of("case-1"),
                docRecall8, docRecall8, docRecall8,
                chunkRecall8, chunkRecall8, chunkRecall8,
                docRecall8, docRecall8,
                factCoverage, forbiddenHit, citationSupport, 1.0, null, null,
                10L, 20L, 30L, 40L,
                1,
                "unavailable", "unavailable", "unavailable",
                Map.of(), 5 - failedCases, failedCases,
                "prompt-v1", "text-embedding-v3", "knowledge:rrf:v1", null, "end-to-end",
                finalEvidenceCoverageAt8, finalEvidenceCoverageAt8, finalEvidenceCoverageAt8, 0.0, 0, 0, 0, 0.0, null, null, chunkRecall8, "single_any_evidence;cross_version_requirement_group");
    }
}
