package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldCaseSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * requirement_key：组内 OR、组间 AND；partial multi-evidence 与 rerank 诊断。
 */
class RagGoldEvidenceMatcherRequirementGroupTest {

    private static final UUID DOC_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DOC_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID VER_A = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID VER_B = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID CHUNK_A1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHUNK_A2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CHUNK_B1 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private RagGoldEvidenceMatcher matcher;
    private Map<UUID, Integer> versionNumbers;

    @BeforeEach
    void setUp() {
        KnowledgeDocumentVersionRepository versions = mock(KnowledgeDocumentVersionRepository.class);
        KnowledgeDocumentVersion versionA = mock(KnowledgeDocumentVersion.class);
        when(versionA.getPublicId()).thenReturn(VER_A);
        when(versionA.getVersionNo()).thenReturn(2);
        KnowledgeDocumentVersion versionB = mock(KnowledgeDocumentVersion.class);
        when(versionB.getPublicId()).thenReturn(VER_B);
        when(versionB.getVersionNo()).thenReturn(2);
        when(versions.findByPublicIdIn(Set.of(VER_A, VER_B))).thenReturn(List.of(versionA, versionB));
        matcher = new RagGoldEvidenceMatcher(versions);
        versionNumbers = Map.of(VER_A, 2, VER_B, 2);
    }

    @Test
    void orWithinSameRequirementKey() {
        List<RagGoldEvidenceSnapshot> evidences = List.of(
                new RagGoldEvidenceSnapshot(
                        RagGoldEvidenceGranularity.CHUNK, DOC_A, VER_A, CHUNK_A1, "settlement-delay"),
                new RagGoldEvidenceSnapshot(
                        RagGoldEvidenceGranularity.CHUNK, DOC_A, VER_A, CHUNK_A2, "settlement-delay"));
        List<String> ranked = List.of("knowledge:" + DOC_A + ":v2:" + CHUNK_A2);

        assertThat(matcher.allRequirementGroupsSatisfiedAtTopK(evidences, ranked, 8, versionNumbers)).isTrue();
    }

    @Test
    void andAcrossRequirementGroupsDev151Style() {
        List<RagGoldEvidenceSnapshot> evidences = List.of(
                new RagGoldEvidenceSnapshot(
                        RagGoldEvidenceGranularity.CHUNK, DOC_A, VER_A, CHUNK_A1, "data-boundary"),
                new RagGoldEvidenceSnapshot(
                        RagGoldEvidenceGranularity.CHUNK, DOC_B, VER_B, CHUNK_B1, "postmortem-p99"));
        List<String> partialHit = List.of("knowledge:" + DOC_A + ":v2:" + CHUNK_A1);
        List<String> fullHit = List.of(
                "knowledge:" + DOC_A + ":v2:" + CHUNK_A1,
                "knowledge:" + DOC_B + ":v2:" + CHUNK_B1);

        assertThat(matcher.allRequirementGroupsSatisfiedAtTopK(evidences, partialHit, 8, versionNumbers))
                .isFalse();
        assertThat(matcher.allRequirementGroupsSatisfiedAtTopK(evidences, fullHit, 8, versionNumbers))
                .isTrue();
    }

    @Test
    void scorerReflectsPartialVsFullRequirementGroupCoverage() {
        KnowledgeDocumentVersionRepository versions = mock(KnowledgeDocumentVersionRepository.class);
        KnowledgeDocumentVersion versionA = mock(KnowledgeDocumentVersion.class);
        when(versionA.getPublicId()).thenReturn(VER_A);
        when(versionA.getVersionNo()).thenReturn(2);
        KnowledgeDocumentVersion versionB = mock(KnowledgeDocumentVersion.class);
        when(versionB.getPublicId()).thenReturn(VER_B);
        when(versionB.getVersionNo()).thenReturn(2);
        when(versions.findByPublicIdIn(Set.of(VER_A, VER_B))).thenReturn(List.of(versionA, versionB));
        RagGoldManualEvaluationScorer scorer = new RagGoldManualEvaluationScorer(new RagGoldEvidenceMatcher(versions));

        RagGoldCaseSnapshot goldCase = new RagGoldCaseSnapshot(
                UUID.randomUUID(),
                "dev-151",
                "问题",
                RagGoldQuestionType.CROSS_DOCUMENT,
                RagGoldDifficulty.MEDIUM,
                false,
                "basis",
                "reviewer",
                List.of(
                        new RagGoldEvidenceSnapshot(
                                RagGoldEvidenceGranularity.CHUNK, DOC_A, VER_A, CHUNK_A1, "data-boundary"),
                        new RagGoldEvidenceSnapshot(
                                RagGoldEvidenceGranularity.CHUNK, DOC_B, VER_B, CHUNK_B1, "postmortem-p99")),
                List.of());

        RagGoldManualCaseScore partial = scorer.scoreCase(
                goldCase,
                List.of("knowledge:" + DOC_A + ":v2:" + CHUNK_A1),
                new RagEvaluationObservation(Set.of(), Set.of(), true),
                "",
                versionNumbers);
        RagGoldManualCaseScore full = scorer.scoreCase(
                goldCase,
                List.of(
                        "knowledge:" + DOC_A + ":v2:" + CHUNK_A1,
                        "knowledge:" + DOC_B + ":v2:" + CHUNK_B1),
                new RagEvaluationObservation(Set.of(), Set.of(), true),
                "",
                versionNumbers);

        assertThat(partial.requirementGroupCoverageAt8()).isFalse();
        assertThat(partial.chunkHitAt8()).isTrue();
        assertThat(full.requirementGroupCoverageAt8()).isTrue();
    }
}
