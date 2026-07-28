package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * RAG 指标必须基于固定证据集合和确定性规则计算，不能由另一个模型自评。
 * 该测试覆盖召回、引用正确性和无依据回答率三个相互独立但可同时报告的指标。
 */
class RagGoldEvaluationRunnerTest {

    @Test
    void computesRecallCitationCorrectnessAndUngroundedAnswerRate() {
        List<RagGoldEvaluationCase> cases = List.of(
                new RagGoldEvaluationCase("release", Set.of("release-a", "release-b")),
                new RagGoldEvaluationCase("issue", Set.of("issue-a", "issue-b")),
                new RagGoldEvaluationCase("no-knowledge", Set.of()));
        RagGoldEvaluationExecutor executor = evaluationCase -> switch (evaluationCase.caseId()) {
            case "release" -> new RagEvaluationObservation(
                    Set.of("release-a", "release-b"), Set.of("release-a"), true);
            case "issue" -> new RagEvaluationObservation(Set.of("issue-a"), Set.of("issue-a"), true);
            default -> new RagEvaluationObservation(Set.of(), Set.of(), false);
        };

        RagEvaluationMetrics metrics = new RagGoldEvaluationRunner().run(cases, executor);

        assertThat(metrics.retrievalRecallRate()).isEqualTo(0.75);
        assertThat(metrics.citationCorrectnessRate()).isEqualTo(1.0);
        assertThat(metrics.ungroundedAnswerRate()).isEqualTo(0.0);
        assertThat(metrics.caseCount()).isEqualTo(3);
    }

    /**
     * 真实检索证据 ID 会追加版本与切片号，金标只应绑定稳定的文档前缀，
     * 否则文档重新切片会把仍然正确的召回误判为缺失。
     */
    @Test
    void matchesExpectedDocumentEvidencePrefixAgainstConcreteChunkEvidence() {
        RagGoldEvaluationCase evaluationCase = new RagGoldEvaluationCase(
                "release", Set.of("knowledge:document-a:"));
        RagGoldEvaluationExecutor executor = ignored -> new RagEvaluationObservation(
                Set.of("knowledge:document-a:v2:chunk-a"),
                Set.of("knowledge:document-a:v2:chunk-a"), true);

        RagEvaluationMetrics metrics = new RagGoldEvaluationRunner().run(List.of(evaluationCase), executor);

        assertThat(metrics.retrievalRecallRate()).isEqualTo(1.0);
    }
}
