package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** CLI 参数校验与回归退出码映射。 */
class RagGoldManualEvaluationCliRunnerTest {

    @Test
    void parseCliArgsFromSpringBootArguments() {
        RagGoldManualEvaluationCliRunner.CliArgs args = RagGoldManualEvaluationCliRunner.CliArgs.parse(
                "--rag-gold-eval",
                "--workspace=1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668",
                "--dataset-key=ops-rag-v1",
                "--dataset-version=dev-240",
                "--split=DEVELOPMENT",
                "--baseline-run-id=abc-def",
                "--output-dir=output/rag-gold-runs");

        assertThat(args.enabled()).isTrue();
        assertThat(args.workspacePublicId()).isEqualTo("1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668");
        assertThat(args.datasetKey()).isEqualTo("ops-rag-v1");
        assertThat(args.datasetVersion()).isEqualTo("dev-240");
        assertThat(args.baselineRunId()).isEqualTo("abc-def");
    }

    @Test
    void rejectsMissingDatasetSelector() {
        RagGoldManualEvaluationCliRunner.CliArgs args = RagGoldManualEvaluationCliRunner.CliArgs.parse(
                "--rag-gold-eval", "--workspace=1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668");

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset");
    }

    @Test
    void parseRetryFailedFromRunId() {
        RagGoldManualEvaluationCliRunner.CliArgs args = RagGoldManualEvaluationCliRunner.CliArgs.parse(
                "--rag-gold-eval",
                "--workspace=1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668",
                "--dataset-key=ops-rag-v1",
                "--dataset-version=dev-240",
                "--retry-from-run=1f18b27f-411b-6e32-adae-07226db09086");

        assertThat(args.retryFromRunId()).isEqualTo("1f18b27f-411b-6e32-adae-07226db09086");
        assertThat(args.retryFromFile()).isNull();
    }

    @Test
    void regressionGateFailureMapsToExitCodeTwo() {
        RagGoldManualEvaluationRegressionGate gate = new RagGoldManualEvaluationRegressionGate();
        RagGoldManualExtendedMetrics baseline = extended(0.90, 0.05);
        RagGoldManualExtendedMetrics candidate = extended(0.50, 0.20);
        assertThat(gate.compare(baseline, candidate).passed()).isFalse();
    }

    private RagGoldManualExtendedMetrics extended(double factCoverage, double forbiddenHit) {
        return new RagGoldManualExtendedMetrics(
                "ops-rag-v1", "frozen-80", "FROZEN", "checksum", java.util.List.of("case-1"),
                0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9,
                factCoverage, forbiddenHit, 0.95, 1.0,
                10L, 20L, 30L, 40L,
                1,
                "unavailable", "unavailable", "unavailable",
                java.util.Map.of(), 1, 0,
                "prompt-v1", "text-embedding-v3", "knowledge:rrf:v1", null, "end-to-end",
                0.9, 0.9, 0.9, 0.0, 0, 0, 0, 0.0, null, null, 0.9, "single_any_evidence;cross_version_requirement_group");
    }
}
