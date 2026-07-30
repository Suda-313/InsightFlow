package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldAssertionSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldCaseSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 扩展评分器必须基于确定性规则计算 Recall@K、MRR、断言与拒答指标。
 */
class RagGoldManualEvaluationScorerTest {

    private static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID VER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CHUNK = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private RagGoldManualEvaluationScorer scorer;
    private Map<UUID, Integer> versionNumbers;

    @BeforeEach
    void setUp() {
        KnowledgeDocumentVersionRepository versions = mock(KnowledgeDocumentVersionRepository.class);
        KnowledgeDocumentVersion version = mock(KnowledgeDocumentVersion.class);
        when(version.getPublicId()).thenReturn(VER);
        when(version.getVersionNo()).thenReturn(2);
        when(versions.findByPublicIdIn(Set.of(VER))).thenReturn(List.of(version));
        RagGoldEvidenceMatcher matcher = new RagGoldEvidenceMatcher(versions);
        scorer = new RagGoldManualEvaluationScorer(matcher);
        versionNumbers = Map.of(VER, 2);
    }

    @Test
    void computesRecallAtKAndMrrForRankedRetrieval() {
        RagGoldCaseSnapshot goldCase = caseWithChunkEvidence();
        List<String> ranked = List.of(
                "knowledge:other:v1:chunk-x",
                "knowledge:" + DOC + ":v2:" + CHUNK,
                "knowledge:" + DOC + ":v2:chunk-y");
        RagEvaluationObservation observation = new RagEvaluationObservation(
                Set.copyOf(ranked), Set.of("knowledge:" + DOC + ":v2:" + CHUNK), true);

        RagGoldManualCaseScore score = scorer.scoreCase(goldCase, ranked, observation, "回答包含关键事实", versionNumbers);

        assertThat(score.documentHitAt1()).isFalse();
        assertThat(score.documentHitAt3()).isTrue();
        assertThat(score.chunkHitAt3()).isTrue();
        assertThat(score.reciprocalRank()).isEqualTo(0.5);
        assertThat(score.ndcgAt8()).isGreaterThan(0.0);
    }

    @Test
    void computesRequiredFactCoverageAndForbiddenClaimHits() {
        RagGoldCaseSnapshot goldCase = new RagGoldCaseSnapshot(
                UUID.randomUUID(), "fact-case", "问题？", RagGoldQuestionType.SINGLE_DOCUMENT_FACT,
                RagGoldDifficulty.EASY, false, "basis", "reviewer",
                List.of(new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.DOCUMENT, DOC, null, null)),
                List.of(
                        new RagGoldAssertionSnapshot(RagGoldAssertionType.REQUIRED_FACT, "必须出现的结论", 1.0),
                        new RagGoldAssertionSnapshot(RagGoldAssertionType.FORBIDDEN_CLAIM, "禁止出现的断言", 1.0)));
        List<String> ranked = List.of("knowledge:" + DOC + ":v1:chunk-a");
        RagEvaluationObservation observation = new RagEvaluationObservation(
                Set.of(ranked.get(0)), Set.of(ranked.get(0)), true);

        RagGoldManualCaseScore compliant = scorer.scoreCase(
                goldCase, ranked, observation, "回答包含必须出现的结论", versionNumbers);
        RagGoldManualCaseScore violated = scorer.scoreCase(
                goldCase, ranked, observation, "回答包含禁止出现的断言", versionNumbers);

        assertThat(compliant.coveredFacts()).isEqualTo(1);
        assertThat(compliant.hitForbiddenClaims()).isZero();
        assertThat(violated.coveredFacts()).isZero();
        assertThat(violated.hitForbiddenClaims()).isEqualTo(1);
    }

    @Test
    void evaluatesShouldRefuseComplianceWithoutKnowledgeClaim() {
        RagGoldCaseSnapshot refusalCase = new RagGoldCaseSnapshot(
                UUID.randomUUID(), "refusal-case", "无法回答的问题", RagGoldQuestionType.REFUSAL,
                RagGoldDifficulty.EASY, true, "basis", "reviewer", List.of(),
                List.of(new RagGoldAssertionSnapshot(RagGoldAssertionType.FORBIDDEN_CLAIM, "胡编结论", 1.0)));
        RagEvaluationObservation refused = new RagEvaluationObservation(Set.of(), Set.of(), false);
        RagEvaluationObservation hallucinated = new RagEvaluationObservation(
                Set.of("knowledge:x:v1:c"), Set.of("knowledge:x:v1:c"), true);

        RagGoldManualCaseScore compliant = scorer.scoreCase(refusalCase, List.of(), refused, "未检索到已发布企业知识", versionNumbers);
        RagGoldManualCaseScore violated = scorer.scoreCase(refusalCase, List.of(), hallucinated, "胡编结论", versionNumbers);

        assertThat(compliant.refusalCompliant()).isTrue();
        assertThat(violated.refusalCompliant()).isFalse();
    }

    @Test
    void aggregatesExtendedMetricsWithLegacyFields() {
        RagGoldManualCaseScore score = new RagGoldManualCaseScore(
                "case-1", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, false, true,
                true, true, true, true, true, true, 1.0, 1.0,
                2, 2, 1, 0, 1.0, true,
                new RagEvaluationObservation(Set.of("knowledge:" + DOC + ":v2:" + CHUNK), Set.of(), true),
                null, null, true);
        RagGoldManualRunContext context = new RagGoldManualRunContext(
                "ops-rag-v1", "dev-240", "DEVELOPMENT", "abc123", List.of("case-1"),
                "prompt-v1", "text-embedding-v3", "knowledge:rrf:v1");
        RagEvaluationMetrics metrics = scorer.aggregateWithLegacy(
                1.0, 1.0, 0.0, List.of(score),
                List.of(new RagGoldManualCaseExecutionMeta("case-1", "succeeded", null, 10L, 20L, 30L)),
                context);

        assertThat(metrics.retrievalRecallRate()).isEqualTo(1.0);
        assertThat(metrics.extended()).isNotNull();
        assertThat(metrics.extended().documentRecallAt8()).isEqualTo(1.0);
        assertThat(metrics.extended().requiredFactCoverageRate()).isEqualTo(1.0);
        assertThat(metrics.extended().retrievalP50Ms()).isEqualTo(10L);
        assertThat(metrics.extended().latencySampleCount()).isEqualTo(1);
    }

    @Test
    void skipsNullLatenciesWhenAggregatingPercentiles() {
        List<RagGoldManualCaseScore> scores = List.of(
                caseScore("a", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, false, true),
                caseScore("b", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, false, true));
        List<RagGoldManualCaseExecutionMeta> metas = List.of(
                new RagGoldManualCaseExecutionMeta("a", "succeeded", null, 100L, 500L, 600L),
                new RagGoldManualCaseExecutionMeta("b", "succeeded", null, null, null, null));
        RagGoldManualRunContext context = new RagGoldManualRunContext(
                "ops-rag-v1", "dev-240", "DEVELOPMENT", "abc123", List.of("a", "b"),
                "prompt-v1", "text-embedding-v3", "knowledge:rrf:v1");

        RagEvaluationMetrics metrics = scorer.aggregate(scores, metas, context);

        assertThat(metrics.extended().latencySampleCount()).isEqualTo(1);
        assertThat(metrics.extended().retrievalP50Ms()).isEqualTo(100L);
        assertThat(metrics.extended().generationP50Ms()).isEqualTo(500L);
    }

    @Test
    void longAssertionMatchesParaphrasedAnswerForFactCoverage() {
        RagGoldCaseSnapshot goldCase = new RagGoldCaseSnapshot(
                UUID.randomUUID(), "dev-031", "问题", RagGoldQuestionType.WORKSPACE_BOUNDARY,
                RagGoldDifficulty.MEDIUM, false, "basis", "reviewer",
                List.of(new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.DOCUMENT, DOC, null, null)),
                List.of(new RagGoldAssertionSnapshot(
                        RagGoldAssertionType.REQUIRED_FACT,
                        "本模块为组织级游戏 Workspace 提供统一数据口径，不得用于跨游戏身份识别",
                        1.0)));
        List<String> ranked = List.of("knowledge:" + DOC + ":v1:chunk-a");
        RagEvaluationObservation observation = new RagEvaluationObservation(
                Set.of(ranked.get(0)), Set.of(ranked.get(0)), true);

        RagGoldManualCaseScore score = scorer.scoreCase(
                goldCase,
                ranked,
                observation,
                "该模块面向组织级 Workspace 统一数据口径，不支持跨游戏身份混用",
                versionNumbers);

        assertThat(score.coveredFacts()).isEqualTo(1);
    }

    @Test
    void aggregatesRefusalComplianceOnlyOverShouldRefuseCases() {
        // 5 道应拒答题仅 1 道合规 → 0.2；另 4 道非拒答题 refusalCompliant 默认 true，不得抬升分子。
        List<RagGoldManualCaseScore> scores = List.of(
                caseScore("refusal-ok", RagGoldQuestionType.REFUSAL, true, true),
                caseScore("refusal-bad-1", RagGoldQuestionType.REFUSAL, true, false),
                caseScore("refusal-bad-2", RagGoldQuestionType.REFUSAL, true, false),
                caseScore("refusal-bad-3", RagGoldQuestionType.REFUSAL, true, false),
                caseScore("refusal-bad-4", RagGoldQuestionType.REFUSAL, true, false),
                caseScore("normal-1", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, false, true),
                caseScore("normal-2", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, false, true),
                caseScore("normal-3", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, false, true),
                caseScore("normal-4", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, false, true));
        RagGoldManualRunContext context = new RagGoldManualRunContext(
                "ops-rag-v1", "dev-240", "DEVELOPMENT", "abc123", List.of(),
                "prompt-v1", "text-embedding-v3", "knowledge:rrf:v1");

        RagEvaluationMetrics metrics = scorer.aggregate(
                scores,
                List.of(new RagGoldManualCaseExecutionMeta("refusal-ok", "succeeded", null, 10L, 20L, 30L)),
                context);

        assertThat(metrics.extended().shouldRefuseComplianceRate()).isEqualTo(0.2);
        assertThat(metrics.extended().byQuestionType().get("REFUSAL").shouldRefuseComplianceRate()).isEqualTo(0.2);
        assertThat(metrics.extended().byQuestionType().get("SINGLE_DOCUMENT_FACT").shouldRefuseComplianceRate()).isNull();
    }

    @Test
    void aggregatesRequirementCoverageAndRerankMovementByQuestionType() {
        List<RagGoldManualCaseScore> scores = List.of(
                diagnosedScore("cross-gain", RagGoldQuestionType.CROSS_DOCUMENT, true, 12, 3, true),
                diagnosedScore("cross-loss", RagGoldQuestionType.CROSS_DOCUMENT, false, 2, 0, false),
                diagnosedScore("single-demoted", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, true, 1, 5, false),
                diagnosedScore("fallback", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, false, 1, 0, false, true));
        RagGoldManualRunContext context = new RagGoldManualRunContext(
                "ops-rag-v1", "dev-240", "DEVELOPMENT", "abc123", List.of(),
                "prompt-v1", "text-embedding-v3", "knowledge:rrf:v3+rerank:test");

        RagGoldManualExtendedMetrics metrics = scorer.aggregate(
                        scores,
                        List.of(new RagGoldManualCaseExecutionMeta(
                                "cross-gain", "succeeded", null, 10L, null, 10L)),
                        context)
                .extended();

        assertThat(metrics.finalEvidenceCoverageAt8()).isEqualTo(0.5);
        assertThat(metrics.rerankGainedCaseCount()).isEqualTo(1);
        assertThat(metrics.rerankLostCaseCount()).isEqualTo(1);
        assertThat(metrics.rerankDemotedCaseCount()).isEqualTo(2);
        RagGoldQuestionTypeMetrics cross = metrics.byQuestionType().get("CROSS_DOCUMENT");
        assertThat(cross.finalEvidenceCoverageAt8()).isEqualTo(0.5);
        assertThat(cross.finalCrossDocumentDualHitAt8()).isEqualTo(0.5);
        assertThat(cross.rerankGainedCaseCount()).isEqualTo(1);
        assertThat(cross.rerankLostCaseCount()).isEqualTo(1);
        assertThat(cross.rerankDemotedCaseCount()).isEqualTo(1);
        assertThat(cross.primaryMetricName()).isEqualTo("requirement_group_coverage_at8");
        assertThat(cross.primaryRecallAt8()).isEqualTo(0.5);
        RagGoldQuestionTypeMetrics single = metrics.byQuestionType().get("SINGLE_DOCUMENT_FACT");
        assertThat(single.primaryMetricName()).isEqualTo("chunk_recall_at8");
    }

    @Test
    void primaryRecallUsesRequirementGroupForCrossButChunkForSingle() {
        List<RagGoldManualCaseScore> scores = List.of(
                diagnosedScore("cross-partial", RagGoldQuestionType.CROSS_DOCUMENT, false, 2, 2, true),
                diagnosedScore("single-hit", RagGoldQuestionType.SINGLE_DOCUMENT_FACT, false, 1, 1, false));
        RagGoldManualRunContext context = new RagGoldManualRunContext(
                "ops-rag-v1", "dev-240", "DEVELOPMENT", "abc123", List.of(),
                "prompt-v1", "text-embedding-v3", "knowledge:rrf:v3");

        RagGoldManualExtendedMetrics metrics = scorer.aggregate(scores, List.of(), context).extended();

        assertThat(metrics.primaryRecallAt8()).isEqualTo(0.5);
        assertThat(metrics.chunkRecallAt8()).isEqualTo(1.0);
        assertThat(metrics.requirementGroupCoverageAt8()).isEqualTo(0.0);
    }

    @Test
    void documentRecallHitsWhenSameDocumentDifferentChunkRetrieved() {
        UUID wrongChunk = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        RagGoldCaseSnapshot goldCase = new RagGoldCaseSnapshot(
                UUID.randomUUID(), "doc-vs-chunk", "问题", RagGoldQuestionType.SINGLE_DOCUMENT_FACT,
                RagGoldDifficulty.MEDIUM, false, "basis", "reviewer",
                List.of(new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.CHUNK, DOC, VER, CHUNK)),
                List.of());
        List<String> ranked = List.of("knowledge:" + DOC + ":v2:" + wrongChunk);
        RagEvaluationObservation observation = new RagEvaluationObservation(
                Set.copyOf(ranked), Set.of(ranked.get(0)), true);

        RagGoldManualCaseScore score = scorer.scoreCase(goldCase, ranked, observation, "回答", versionNumbers);

        assertThat(score.documentHitAt1()).isTrue();
        assertThat(score.chunkHitAt1()).isFalse();
    }

    private RagGoldManualCaseScore caseScore(
            String caseKey, RagGoldQuestionType questionType, boolean shouldRefuse, boolean refusalCompliant) {
        return new RagGoldManualCaseScore(
                caseKey, questionType, shouldRefuse, false,
                false, false, false, false, false, false, 0.0, 0.0,
                0, 0, 0, 0, 1.0, refusalCompliant,
                new RagEvaluationObservation(Set.of(), Set.of(), false),
                null, null, false);
    }

    private RagGoldManualCaseScore diagnosedScore(
            String caseKey,
            RagGoldQuestionType questionType,
            boolean requirementCoverage,
            int beforeRank,
            int afterRank,
            boolean crossDualHit) {
        return diagnosedScore(
                caseKey, questionType, requirementCoverage, beforeRank, afterRank, crossDualHit, false);
    }

    private RagGoldManualCaseScore diagnosedScore(
            String caseKey,
            RagGoldQuestionType questionType,
            boolean requirementCoverage,
            int beforeRank,
            int afterRank,
            boolean crossDualHit,
            boolean fallback) {
        RagGoldRetrievalCaseDiagnostics diagnostics = new RagGoldRetrievalCaseDiagnostics(
                beforeRank,
                beforeRank > 0 && beforeRank <= 10,
                beforeRank > 0 && beforeRank <= 30,
                beforeRank > 0 && beforeRank <= 50,
                beforeRank,
                afterRank,
                "cross-encoder",
                10L,
                fallback,
                30,
                List.of(),
                List.of(),
                requirementCoverage,
                crossDualHit,
                List.of(),
                List.of(),
                List.of());
        return new RagGoldManualCaseScore(
                caseKey, questionType, false, true,
                true, true, true, afterRank == 1, afterRank > 0 && afterRank <= 3, afterRank > 0,
                afterRank == 0 ? 0.0 : 1.0 / afterRank, afterRank == 0 ? 0.0 : 1.0,
                0, 0, 0, 0, 1.0, true,
                new RagEvaluationObservation(Set.of(), Set.of(), false),
                null, diagnostics, requirementCoverage);
    }

    private RagGoldCaseSnapshot caseWithChunkEvidence() {
        return new RagGoldCaseSnapshot(
                UUID.randomUUID(), "chunk-case", "chunk 问题", RagGoldQuestionType.SINGLE_DOCUMENT_FACT,
                RagGoldDifficulty.MEDIUM, false, "basis", "reviewer",
                List.of(new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.CHUNK, DOC, VER, CHUNK)),
                List.of(new RagGoldAssertionSnapshot(RagGoldAssertionType.REQUIRED_FACT, "关键事实", 1.0)));
    }
}
