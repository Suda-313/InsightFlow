package com.insightflow.evaluation.rag;

import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldAssertionSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldCaseSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 人工金标 RAG 评测的扩展确定性评分器。
 *
 * <p>分别计算 document/chunk Recall@K、MRR、nDCG@8、断言覆盖、引用支撑与拒答合规；
 * 不调用第二个模型，也不持久化回答正文。</p>
 */
@Component
public class RagGoldManualEvaluationScorer {

    private final RagGoldEvidenceMatcher evidenceMatcher;
    private final RagGoldAssertionMatcher assertionMatcher = new RagGoldAssertionMatcher();

    public RagGoldManualEvaluationScorer(RagGoldEvidenceMatcher evidenceMatcher) {
        this.evidenceMatcher = evidenceMatcher;
    }

    /** 单题评分：仅最终 Top8 检索结果，不含候选漏斗。 */
    public RagGoldManualCaseScore scoreCase(
            RagGoldCaseSnapshot goldCase,
            List<String> rankedRetrievedIds,
            RagEvaluationObservation observation,
            String answer,
            Map<UUID, Integer> versionNumbers) {
        return scoreCase(
                goldCase, rankedRetrievedIds, List.of(), null, observation, answer, versionNumbers, null);
    }

    /** 单题评分：可附带 RRF 候选 Top50 与来源统计，供 retrieval-only 漏斗使用。 */
    public RagGoldManualCaseScore scoreCase(
            RagGoldCaseSnapshot goldCase,
            List<String> rankedFinalIds,
            List<String> rankedCandidateIds,
            RagGoldCaseRetrievalFunnel candidateSourceCounts,
            RagEvaluationObservation observation,
            String answer,
            Map<UUID, Integer> versionNumbers) {
        return scoreCase(
                goldCase,
                rankedFinalIds,
                rankedCandidateIds,
                candidateSourceCounts,
                observation,
                answer,
                versionNumbers,
                null);
    }

    /** 单题评分：可附带完整检索诊断（候选 + 精排 + requirement 组覆盖）。 */
    public RagGoldManualCaseScore scoreCase(
            RagGoldCaseSnapshot goldCase,
            List<String> rankedFinalIds,
            List<String> rankedCandidateIds,
            RagGoldCaseRetrievalFunnel candidateSourceCounts,
            RagEvaluationObservation observation,
            String answer,
            Map<UUID, Integer> versionNumbers,
            RagGoldRetrievalCaseDiagnostics retrievalDiagnostics) {
        List<RagGoldEvidenceSnapshot> evidences = goldCase.evidences();
        boolean documentHit1 = evidenceMatcher.hitWithinTopK(
                evidences, rankedFinalIds, 1, versionNumbers, RagGoldEvidenceGranularity.DOCUMENT);
        boolean documentHit3 = evidenceMatcher.hitWithinTopK(
                evidences, rankedFinalIds, 3, versionNumbers, RagGoldEvidenceGranularity.DOCUMENT);
        boolean documentHit8 = evidenceMatcher.hitWithinTopK(
                evidences, rankedFinalIds, 8, versionNumbers, RagGoldEvidenceGranularity.DOCUMENT);
        boolean chunkHit1 = evidenceMatcher.hitWithinTopK(
                evidences, rankedFinalIds, 1, versionNumbers, RagGoldEvidenceGranularity.CHUNK);
        boolean chunkHit3 = evidenceMatcher.hitWithinTopK(
                evidences, rankedFinalIds, 3, versionNumbers, RagGoldEvidenceGranularity.CHUNK);
        boolean chunkHit8 = evidenceMatcher.hitWithinTopK(
                evidences, rankedFinalIds, 8, versionNumbers, RagGoldEvidenceGranularity.CHUNK);
        int firstRank = evidenceMatcher.firstRelevantRank(evidences, rankedFinalIds, versionNumbers);
        double reciprocalRank = firstRank == 0 ? 0.0 : 1.0 / firstRank;
        double ndcgAt8 = ndcgAt8(evidences, rankedFinalIds, versionNumbers);

        int requiredFacts = 0;
        int coveredFacts = 0;
        int forbiddenClaims = 0;
        int hitForbiddenClaims = 0;
        String normalizedAnswer = normalize(answer);
        for (RagGoldAssertionSnapshot assertion : goldCase.assertions()) {
            if (assertion.assertionType() == RagGoldAssertionType.REQUIRED_FACT) {
                requiredFacts++;
                if (assertionMatcher.matches(normalizedAnswer, assertion.assertionText())) {
                    coveredFacts++;
                }
            } else if (assertion.assertionType() == RagGoldAssertionType.FORBIDDEN_CLAIM) {
                forbiddenClaims++;
                if (assertionMatcher.matches(normalizedAnswer, assertion.assertionText())) {
                    hitForbiddenClaims++;
                }
            }
        }

        int citedCount = observation.citedEvidenceIds().size();
        int supportedCitations = (int) observation.citedEvidenceIds().stream()
                .filter(observation.retrievedEvidenceIds()::contains)
                .count();
        double citationSupportRate = citedCount == 0 ? 1.0 : (double) supportedCitations / citedCount;

        boolean refusalCompliant = !goldCase.shouldRefuse()
                || (!observation.containsKnowledgeClaim() && hitForbiddenClaims == 0);

        RagGoldCaseRetrievalFunnel retrievalFunnel = rankedCandidateIds.isEmpty()
                ? null
                : buildRetrievalFunnel(goldCase, evidences, rankedCandidateIds, candidateSourceCounts, versionNumbers);

        boolean requirementGroupCoverageAt8 = evidenceMatcher.allRequirementGroupsSatisfiedAtTopK(
                evidences, rankedFinalIds, 8, versionNumbers);

        return new RagGoldManualCaseScore(
                goldCase.caseKey(),
                goldCase.questionType(),
                goldCase.shouldRefuse(),
                !evidences.isEmpty(),
                documentHit1,
                documentHit3,
                documentHit8,
                chunkHit1,
                chunkHit3,
                chunkHit8,
                reciprocalRank,
                ndcgAt8,
                requiredFacts,
                coveredFacts,
                forbiddenClaims,
                hitForbiddenClaims,
                citationSupportRate,
                refusalCompliant,
                observation,
                retrievalFunnel,
                retrievalDiagnostics,
                requirementGroupCoverageAt8);
    }

    private RagGoldCaseRetrievalFunnel buildRetrievalFunnel(
            RagGoldCaseSnapshot goldCase,
            List<RagGoldEvidenceSnapshot> evidences,
            List<String> rankedCandidateIds,
            RagGoldCaseRetrievalFunnel candidateSourceCounts,
            Map<UUID, Integer> versionNumbers) {
        boolean candidateDocumentHitAt10 = evidenceMatcher.hitWithinTopK(
                evidences, rankedCandidateIds, 10, versionNumbers, RagGoldEvidenceGranularity.DOCUMENT);
        boolean candidateDocumentHitAt30 = evidenceMatcher.hitWithinTopK(
                evidences, rankedCandidateIds, 30, versionNumbers, RagGoldEvidenceGranularity.DOCUMENT);
        boolean candidateDocumentHitAt50 = evidenceMatcher.hitWithinTopK(
                evidences, rankedCandidateIds, 50, versionNumbers, RagGoldEvidenceGranularity.DOCUMENT);
        boolean candidateChunkHitAt10 = evidenceMatcher.hitWithinTopK(
                evidences, rankedCandidateIds, 10, versionNumbers, RagGoldEvidenceGranularity.CHUNK);
        boolean candidateChunkHitAt30 = evidenceMatcher.hitWithinTopK(
                evidences, rankedCandidateIds, 30, versionNumbers, RagGoldEvidenceGranularity.CHUNK);
        boolean candidateChunkHitAt50 = evidenceMatcher.hitWithinTopK(
                evidences, rankedCandidateIds, 50, versionNumbers, RagGoldEvidenceGranularity.CHUNK);
        boolean crossDualHit = goldCase.questionType() != RagGoldQuestionType.CROSS_DOCUMENT
                || evidenceMatcher.distinctDocumentsHitWithinTopK(evidences, rankedCandidateIds, 50) >= 2;
        int lexicalOnly = candidateSourceCounts == null ? 0 : candidateSourceCounts.lexicalOnlyCandidates();
        int vectorOnly = candidateSourceCounts == null ? 0 : candidateSourceCounts.vectorOnlyCandidates();
        int both = candidateSourceCounts == null ? 0 : candidateSourceCounts.bothSourceCandidates();
        return new RagGoldCaseRetrievalFunnel(
                candidateDocumentHitAt10,
                candidateDocumentHitAt30,
                candidateDocumentHitAt50,
                candidateChunkHitAt10,
                candidateChunkHitAt30,
                candidateChunkHitAt50,
                crossDualHit,
                lexicalOnly,
                vectorOnly,
                both);
    }

    /** 聚合多题得分与运行元数据，产出 legacy + extended 指标。 */
    public RagEvaluationMetrics aggregate(
            List<RagGoldManualCaseScore> caseScores,
            List<RagGoldManualCaseExecutionMeta> executionMetas,
            RagGoldManualRunContext context) {
        int caseCount = caseScores.size();
        int succeeded = (int) executionMetas.stream().filter(meta -> "succeeded".equals(meta.status())).count();
        int failed = caseCount - succeeded;

        int documentCases = (int) caseScores.stream().filter(RagGoldManualCaseScore::hasExpectedEvidence).count();
        int chunkCases = documentCases;

        double documentRecallAt1 = ratio(caseScores.stream().filter(RagGoldManualCaseScore::documentHitAt1).count(), documentCases);
        double documentRecallAt3 = ratio(caseScores.stream().filter(RagGoldManualCaseScore::documentHitAt3).count(), documentCases);
        double documentRecallAt8 = ratio(caseScores.stream().filter(RagGoldManualCaseScore::documentHitAt8).count(), documentCases);
        double chunkRecallAt1 = ratio(caseScores.stream().filter(RagGoldManualCaseScore::chunkHitAt1).count(), chunkCases);
        double chunkRecallAt3 = ratio(caseScores.stream().filter(RagGoldManualCaseScore::chunkHitAt3).count(), chunkCases);
        double chunkRecallAt8 = ratio(caseScores.stream().filter(RagGoldManualCaseScore::chunkHitAt8).count(), chunkCases);
        double mrrSum = caseScores.stream().mapToDouble(RagGoldManualCaseScore::reciprocalRank).sum();
        double mrr = documentCases == 0 ? 0.0 : mrrSum / documentCases;
        double ndcgSum = caseScores.stream().mapToDouble(RagGoldManualCaseScore::ndcgAt8).sum();
        double ndcgAt8 = documentCases == 0 ? 0.0 : ndcgSum / documentCases;

        int totalRequired = caseScores.stream().mapToInt(RagGoldManualCaseScore::requiredFacts).sum();
        int coveredRequired = caseScores.stream().mapToInt(RagGoldManualCaseScore::coveredFacts).sum();
        int totalForbidden = caseScores.stream().mapToInt(RagGoldManualCaseScore::forbiddenClaims).sum();
        int hitForbidden = caseScores.stream().mapToInt(RagGoldManualCaseScore::hitForbiddenClaims).sum();

        double requiredFactCoverage = ratio(coveredRequired, totalRequired);
        double forbiddenHitRate = ratio(hitForbidden, totalForbidden);
        double citationSupport = caseScores.isEmpty()
                ? 0.0
                : caseScores.stream().mapToDouble(RagGoldManualCaseScore::citationSupportRate).average().orElse(0.0);

        long refusalCases = caseScores.stream().filter(RagGoldManualCaseScore::shouldRefuse).count();
        // 拒答合规率分母仅统计 shouldRefuse 题；非拒答题默认 refusalCompliant=true，不得计入分子。
        long refusalCompliantCases = caseScores.stream()
                .filter(score -> score.shouldRefuse() && score.refusalCompliant())
                .count();
        Double refusalCompliance = refusalCases == 0
                ? null
                : ratio(refusalCompliantCases, (int) refusalCases);

        List<Long> retrievalLatencies = latencySamples(executionMetas, RagGoldManualCaseExecutionMeta::retrievalLatencyMs);
        List<Long> generationLatencies = latencySamples(executionMetas, RagGoldManualCaseExecutionMeta::generationLatencyMs);
        int latencySampleCount = retrievalLatencies.size();

        Map<String, RagGoldQuestionTypeMetrics> byQuestionType = aggregateByQuestionType(caseScores);

        RagGoldRetrievalFunnelAggregate retrievalFunnel = aggregateRetrievalFunnel(caseScores, context.evaluationMode());

        int evidenceCaseCount = (int) caseScores.stream().filter(RagGoldManualCaseScore::hasExpectedEvidence).count();
        double finalEvidenceCoverageAt8 = ratio(
                caseScores.stream().filter(RagGoldManualCaseScore::requirementGroupCoverageAt8).count(),
                evidenceCaseCount);
        double primaryRecallAt8 = ratio(
                caseScores.stream().filter(this::primaryHitAt8).count(),
                evidenceCaseCount);
        long crossCases = caseScores.stream()
                .filter(score -> score.questionType() == RagGoldQuestionType.CROSS_DOCUMENT)
                .count();
        long crossDualHitsFinal = caseScores.stream()
                .filter(score -> score.questionType() == RagGoldQuestionType.CROSS_DOCUMENT)
                .filter(score -> score.retrievalDiagnostics() != null
                        && score.retrievalDiagnostics().finalCrossDocumentDualHitAt8())
                .count();
        double finalCrossDocumentDualHitAt8 = crossCases == 0
                ? 0.0
                : ratio(crossDualHitsFinal, (int) crossCases);
        int rerankGainedCaseCount = (int) caseScores.stream().filter(this::isRerankGain).count();
        int rerankLostCaseCount = (int) caseScores.stream().filter(this::isRerankLoss).count();
        int rerankDemotedCaseCount = (int) caseScores.stream().filter(this::isRerankDemotion).count();

        List<RagGoldManualCaseScore> withRerankDiagnostics = caseScores.stream()
                .filter(score -> score.retrievalDiagnostics() != null
                        && score.retrievalDiagnostics().rerankLatencyMs() != null)
                .toList();
        long rerankFallbackCount = withRerankDiagnostics.stream()
                .filter(score -> score.retrievalDiagnostics().rerankFallbackUsed())
                .count();
        double rerankFallbackRate = withRerankDiagnostics.isEmpty()
                ? 0.0
                : ratio(rerankFallbackCount, withRerankDiagnostics.size());
        List<Long> rerankLatencies = withRerankDiagnostics.stream()
                .map(score -> score.retrievalDiagnostics().rerankLatencyMs())
                .sorted()
                .toList();

        RagGoldManualExtendedMetrics extended = new RagGoldManualExtendedMetrics(
                context.datasetKey(),
                context.datasetVersionLabel(),
                context.split(),
                context.checksum(),
                context.caseKeys(),
                documentRecallAt1,
                documentRecallAt3,
                documentRecallAt8,
                chunkRecallAt1,
                chunkRecallAt3,
                chunkRecallAt8,
                mrr,
                ndcgAt8,
                requiredFactCoverage,
                forbiddenHitRate,
                citationSupport,
                refusalCompliance,
                percentile(retrievalLatencies, 50),
                percentile(retrievalLatencies, 95),
                percentile(generationLatencies, 50),
                percentile(generationLatencies, 95),
                latencySampleCount,
                "unavailable",
                "unavailable",
                "unavailable",
                byQuestionType,
                succeeded,
                failed,
                context.promptVersion(),
                context.embeddingModel(),
                context.retrievalConfigVersion(),
                retrievalFunnel,
                context.evaluationMode(),
                finalEvidenceCoverageAt8,
                finalEvidenceCoverageAt8,
                primaryRecallAt8,
                finalCrossDocumentDualHitAt8,
                rerankGainedCaseCount,
                rerankLostCaseCount,
                rerankDemotedCaseCount,
                rerankFallbackRate,
                percentile(rerankLatencies, 50),
                percentile(rerankLatencies, 95),
                chunkRecallAt8,
                "single_any_evidence;cross_version_requirement_group");

        return new RagEvaluationMetrics(0, 0, 0, caseCount, extended);
    }

    /** 带 legacy 前缀的完整聚合：Runner 传入 legacy 三项指标。 */
    public RagEvaluationMetrics aggregateWithLegacy(
            double retrievalRecallRate,
            double citationCorrectnessRate,
            double ungroundedAnswerRate,
            List<RagGoldManualCaseScore> caseScores,
            List<RagGoldManualCaseExecutionMeta> executionMetas,
            RagGoldManualRunContext context) {
        RagEvaluationMetrics base = aggregate(caseScores, executionMetas, context);
        return new RagEvaluationMetrics(
                retrievalRecallRate,
                citationCorrectnessRate,
                ungroundedAnswerRate,
                base.caseCount(),
                base.extended());
    }

    private RagGoldRetrievalFunnelAggregate aggregateRetrievalFunnel(
            List<RagGoldManualCaseScore> caseScores, String evaluationMode) {
        List<RagGoldManualCaseScore> withFunnel = caseScores.stream()
                .filter(score -> score.retrievalFunnel() != null)
                .toList();
        if (withFunnel.isEmpty()) {
            return null;
        }
        int evidenceCases = (int) withFunnel.stream().filter(RagGoldManualCaseScore::hasExpectedEvidence).count();
        long crossCases = withFunnel.stream()
                .filter(score -> score.questionType() == RagGoldQuestionType.CROSS_DOCUMENT)
                .count();
        long crossDualHits = withFunnel.stream()
                .filter(score -> score.questionType() == RagGoldQuestionType.CROSS_DOCUMENT)
                .filter(score -> score.retrievalFunnel().crossDocumentDualDocumentHit())
                .count();
        return new RagGoldRetrievalFunnelAggregate(
                ratio(withFunnel.stream().filter(s -> s.retrievalFunnel().candidateDocumentHitAt10()).count(), evidenceCases),
                ratio(withFunnel.stream().filter(s -> s.retrievalFunnel().candidateDocumentHitAt30()).count(), evidenceCases),
                ratio(withFunnel.stream().filter(s -> s.retrievalFunnel().candidateDocumentHitAt50()).count(), evidenceCases),
                ratio(withFunnel.stream().filter(s -> s.retrievalFunnel().candidateChunkHitAt10()).count(), evidenceCases),
                ratio(withFunnel.stream().filter(s -> s.retrievalFunnel().candidateChunkHitAt30()).count(), evidenceCases),
                ratio(withFunnel.stream().filter(s -> s.retrievalFunnel().candidateChunkHitAt50()).count(), evidenceCases),
                crossCases == 0 ? 0.0 : ratio(crossDualHits, (int) crossCases),
                withFunnel.stream().mapToInt(s -> s.retrievalFunnel().lexicalOnlyCandidates()).sum(),
                withFunnel.stream().mapToInt(s -> s.retrievalFunnel().vectorOnlyCandidates()).sum(),
                withFunnel.stream().mapToInt(s -> s.retrievalFunnel().bothSourceCandidates()).sum(),
                evaluationMode);
    }

    private Map<String, RagGoldQuestionTypeMetrics> aggregateByQuestionType(List<RagGoldManualCaseScore> caseScores) {
        Map<RagGoldQuestionType, List<RagGoldManualCaseScore>> grouped = new LinkedHashMap<>();
        for (RagGoldManualCaseScore score : caseScores) {
            grouped.computeIfAbsent(score.questionType(), ignored -> new ArrayList<>()).add(score);
        }
        Map<String, RagGoldQuestionTypeMetrics> result = new LinkedHashMap<>();
        for (Map.Entry<RagGoldQuestionType, List<RagGoldManualCaseScore>> entry : grouped.entrySet()) {
            List<RagGoldManualCaseScore> scores = entry.getValue();
            int withEvidence = (int) scores.stream().filter(RagGoldManualCaseScore::hasExpectedEvidence).count();
            long refusal = scores.stream().filter(RagGoldManualCaseScore::shouldRefuse).count();
            long crossCases = scores.stream()
                    .filter(score -> score.questionType() == RagGoldQuestionType.CROSS_DOCUMENT)
                    .count();
            long crossDualHits = scores.stream()
                    .filter(score -> score.questionType() == RagGoldQuestionType.CROSS_DOCUMENT)
                    .filter(score -> score.retrievalDiagnostics() != null
                            && score.retrievalDiagnostics().finalCrossDocumentDualHitAt8())
                    .count();
            boolean requirementGroupPrimary = usesRequirementGroupPrimary(entry.getKey());
            String primaryMetricName = requirementGroupPrimary
                    ? "requirement_group_coverage_at8"
                    : "chunk_recall_at8";
            double primaryRecall = requirementGroupPrimary
                    ? ratio(scores.stream().filter(RagGoldManualCaseScore::requirementGroupCoverageAt8).count(), withEvidence)
                    : ratio(scores.stream().filter(RagGoldManualCaseScore::chunkHitAt8).count(), withEvidence);
            result.put(entry.getKey().name(), new RagGoldQuestionTypeMetrics(
                    scores.size(),
                    ratio(scores.stream().filter(RagGoldManualCaseScore::documentHitAt8).count(), withEvidence),
                    ratio(scores.stream().filter(RagGoldManualCaseScore::chunkHitAt8).count(), withEvidence),
                    ratio(scores.stream().filter(RagGoldManualCaseScore::requirementGroupCoverageAt8).count(), withEvidence),
                    primaryRecall,
                    primaryMetricName,
                    crossCases == 0 ? 0.0 : ratio(crossDualHits, (int) crossCases),
                    (int) scores.stream().filter(this::isRerankGain).count(),
                    (int) scores.stream().filter(this::isRerankLoss).count(),
                    (int) scores.stream().filter(this::isRerankDemotion).count(),
                    ratio(scores.stream().mapToInt(RagGoldManualCaseScore::coveredFacts).sum(),
                            scores.stream().mapToInt(RagGoldManualCaseScore::requiredFacts).sum()),
                    ratio(scores.stream().mapToInt(RagGoldManualCaseScore::hitForbiddenClaims).sum(),
                            scores.stream().mapToInt(RagGoldManualCaseScore::forbiddenClaims).sum()),
                    refusal == 0 ? null : ratio(
                            scores.stream().filter(score -> score.shouldRefuse() && score.refusalCompliant()).count(),
                            (int) refusal)));
        }
        return result;
    }

    private boolean isRerankGain(RagGoldManualCaseScore score) {
        RagGoldRetrievalCaseDiagnostics diagnostics = score.retrievalDiagnostics();
        return isSuccessfulCrossEncoder(diagnostics)
                && diagnostics.rerankBeforeRank() > 8
                && diagnostics.rerankAfterRank() > 0
                && diagnostics.rerankAfterRank() <= 8;
    }

    private boolean isRerankLoss(RagGoldManualCaseScore score) {
        RagGoldRetrievalCaseDiagnostics diagnostics = score.retrievalDiagnostics();
        return isSuccessfulCrossEncoder(diagnostics)
                && diagnostics.rerankBeforeRank() > 0
                && diagnostics.rerankBeforeRank() <= 8
                && diagnostics.rerankAfterRank() == 0;
    }

    private boolean isRerankDemotion(RagGoldManualCaseScore score) {
        RagGoldRetrievalCaseDiagnostics diagnostics = score.retrievalDiagnostics();
        return isSuccessfulCrossEncoder(diagnostics)
                && diagnostics.rerankBeforeRank() > 0
                && (diagnostics.rerankAfterRank() == 0
                        || diagnostics.rerankAfterRank() > diagnostics.rerankBeforeRank());
    }

    /** fallback 与 RRF-only 不是精排决策，不能污染 gained/lost/demotion 归因。 */
    private boolean isSuccessfulCrossEncoder(RagGoldRetrievalCaseDiagnostics diagnostics) {
        return diagnostics != null
                && !diagnostics.rerankFallbackUsed()
                && "cross-encoder".equals(diagnostics.rerankerName());
    }

    /** CROSS/VERSION 以 requirement 组覆盖为主指标；SINGLE 等仍以 any-evidence chunk R@8 为主。 */
    private boolean usesRequirementGroupPrimary(RagGoldQuestionType questionType) {
        return questionType == RagGoldQuestionType.CROSS_DOCUMENT
                || questionType == RagGoldQuestionType.VERSION_CONFLICT;
    }

    private boolean primaryHitAt8(RagGoldManualCaseScore score) {
        if (!score.hasExpectedEvidence()) {
            return false;
        }
        return usesRequirementGroupPrimary(score.questionType())
                ? score.requirementGroupCoverageAt8()
                : score.chunkHitAt8();
    }

    /** 二元 nDCG@8：每个 rank 位置 relevance 为 0 或 1。 */
    double ndcgAt8(
            List<RagGoldEvidenceSnapshot> expected,
            List<String> rankedIds,
            Map<UUID, Integer> versionNumbers) {
        if (expected.isEmpty()) {
            return 0.0;
        }
        int k = Math.min(8, rankedIds.size());
        double dcg = 0.0;
        for (int rank = 1; rank <= k; rank++) {
            final String rankedId = rankedIds.get(rank - 1);
            boolean relevant = expected.stream()
                    .anyMatch(evidence -> evidenceMatcher.matchesEvidence(
                            evidence, rankedId, versionNumbers));
            if (relevant) {
                dcg += 1.0 / (Math.log(rank + 1) / Math.log(2));
            }
        }
        int idealHits = Math.min(expected.size(), 8);
        double idcg = 0.0;
        for (int rank = 1; rank <= idealHits; rank++) {
            idcg += 1.0 / (Math.log(rank + 1) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private double ratio(long numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private Long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) {
            return null;
        }
        int index = Math.min(sortedValues.size() - 1, (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1);
        return sortedValues.get(Math.max(0, index));
    }

    /** 仅统计 succeeded 且 latency 非 null 的样本，避免 carry-forward 填 0 污染分位。 */
    private List<Long> latencySamples(
            List<RagGoldManualCaseExecutionMeta> executionMetas,
            java.util.function.Function<RagGoldManualCaseExecutionMeta, Long> extractor) {
        return executionMetas.stream()
                .filter(meta -> "succeeded".equals(meta.status()))
                .map(extractor)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    /** 与 {@link RagGoldAssertionMatcher#normalize} 相同；对外保留供测试对齐。 */
    String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replaceAll("[\\s，。；、：！？,.!?;:]", "");
    }
}
