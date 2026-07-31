package com.insightflow.evaluation.rag;

import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.evaluation.rag.gold.RagGoldAssertionSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldCaseSnapshot;
import java.util.List;
import java.util.Set;

/**
 * 将上一轮已持久化的逐题结果还原为聚合器可消费的 score/meta，供失败重跑后与成功题合并。
 *
 * <p>若逐题 JSON 含 hit@K/MRR/nDCG/耗时字段则无损还原；缺字段时回退保守近似（兼容历史批次）。</p>
 */
final class RagGoldManualEvaluationCarryForwardSupport {

    private RagGoldManualEvaluationCarryForwardSupport() {}

    static RagGoldManualCaseScore toScore(
            RagGoldCaseSnapshot goldCase, RagGoldManualEvaluationCaseResult carried) {
        int requiredFacts = countAssertions(goldCase, RagGoldAssertionType.REQUIRED_FACT);
        int forbiddenClaims = countAssertions(goldCase, RagGoldAssertionType.FORBIDDEN_CLAIM);
        int coveredFacts = requiredFacts == 0
                ? 0
                : (int) Math.round(safeDouble(carried.requiredFactCoverageRate()) * requiredFacts);
        int hitForbidden = forbiddenClaims == 0
                ? 0
                : (int) Math.round(safeDouble(carried.forbiddenClaimHitRate()) * forbiddenClaims);
        int cited = safeInt(carried.citedEvidenceCount());
        int correctCitation = safeInt(carried.correctCitationCount());
        double citationSupport = cited == 0 ? 1.0 : (double) correctCitation / cited;
        RagEvaluationObservation observation = new RagEvaluationObservation(
                Set.of(),
                Set.of(),
                !Boolean.TRUE.equals(carried.ungrounded()));

        if (carried.documentHitAt8() != null) {
            return new RagGoldManualCaseScore(
                    goldCase.caseKey(),
                    goldCase.questionType(),
                    goldCase.shouldRefuse(),
                    !goldCase.evidences().isEmpty(),
                    Boolean.TRUE.equals(carried.documentHitAt1()),
                    Boolean.TRUE.equals(carried.documentHitAt3()),
                    Boolean.TRUE.equals(carried.documentHitAt8()),
                    Boolean.TRUE.equals(carried.chunkHitAt1()),
                    Boolean.TRUE.equals(carried.chunkHitAt3()),
                    Boolean.TRUE.equals(carried.chunkHitAt8()),
                    safeDouble(carried.reciprocalRank()),
                    safeDouble(carried.ndcgAt8()),
                    requiredFacts,
                    coveredFacts,
                    forbiddenClaims,
                    hitForbidden,
                    citationSupport,
                    Boolean.TRUE.equals(carried.refusalCompliant()),
                    observation,
                    null,
                    null,
                    carried.retrievalDiagnostics() != null
                            && carried.retrievalDiagnostics().requirementGroupCoverageAt8());
        }

        return legacyApproximateScore(goldCase, carried, requiredFacts, coveredFacts, forbiddenClaims,
                hitForbidden, citationSupport, observation);
    }

    /** 历史 JSON 无 hit@K 字段时的保守近似（Recall@3=@8，MRR/nDCG 二元）。 */
    private static RagGoldManualCaseScore legacyApproximateScore(
            RagGoldCaseSnapshot goldCase,
            RagGoldManualEvaluationCaseResult carried,
            int requiredFacts,
            int coveredFacts,
            int forbiddenClaims,
            int hitForbidden,
            double citationSupport,
            RagEvaluationObservation observation) {
        int expected = safeInt(carried.expectedEvidenceCount());
        int retrievedExpected = safeInt(carried.retrievedExpectedEvidenceCount());
        boolean hitAt8 = expected > 0 && retrievedExpected > 0;
        boolean hitAt1 = expected > 0 && retrievedExpected >= expected;
        return new RagGoldManualCaseScore(
                goldCase.caseKey(),
                goldCase.questionType(),
                goldCase.shouldRefuse(),
                !goldCase.evidences().isEmpty(),
                hitAt1,
                hitAt8,
                hitAt8,
                hitAt1,
                hitAt8,
                hitAt8,
                hitAt8 ? 1.0 : 0.0,
                hitAt8 ? 1.0 : 0.0,
                requiredFacts,
                coveredFacts,
                forbiddenClaims,
                hitForbidden,
                citationSupport,
                Boolean.TRUE.equals(carried.refusalCompliant()),
                observation,
                null,
                null,
                false);
    }

    static RagGoldManualCaseExecutionMeta toExecutionMeta(RagGoldManualEvaluationCaseResult carried) {
        return new RagGoldManualCaseExecutionMeta(
                carried.caseKey(),
                carried.status(),
                carried.failureStage(),
                carried.retrievalLatencyMs(),
                carried.generationLatencyMs(),
                carried.totalLatencyMs(),
                null,
                null,
                null);
    }

    static RagEvaluationMetrics legacyMetricsFromCaseResults(List<RagGoldManualEvaluationCaseResult> caseResults) {
        int totalExpected = caseResults.stream().mapToInt(r -> safeInt(r.expectedEvidenceCount())).sum();
        int totalRetrievedExpected = caseResults.stream()
                .mapToInt(r -> safeInt(r.retrievedExpectedEvidenceCount()))
                .sum();
        int totalCited = caseResults.stream().mapToInt(r -> safeInt(r.citedEvidenceCount())).sum();
        int totalCorrectCitation = caseResults.stream().mapToInt(r -> safeInt(r.correctCitationCount())).sum();
        long ungrounded = caseResults.stream().filter(r -> Boolean.TRUE.equals(r.ungrounded())).count();
        double retrievalRecall = totalExpected == 0 ? 0.0 : (double) totalRetrievedExpected / totalExpected;
        double citationCorrectness = totalCited == 0 ? 1.0 : (double) totalCorrectCitation / totalCited;
        double ungroundedRate = caseResults.isEmpty() ? 0.0 : (double) ungrounded / caseResults.size();
        return new RagEvaluationMetrics(
                retrievalRecall, citationCorrectness, ungroundedRate, caseResults.size(), null);
    }

    private static int countAssertions(RagGoldCaseSnapshot goldCase, RagGoldAssertionType type) {
        int count = 0;
        for (RagGoldAssertionSnapshot assertion : goldCase.assertions()) {
            if (assertion.assertionType() == type) {
                count++;
            }
        }
        return count;
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static double safeDouble(Double value) {
        return value == null ? 0.0 : value;
    }
}
