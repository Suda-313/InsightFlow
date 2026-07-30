package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.knowledge.KnowledgeEvidence;
import com.insightflow.knowledge.KnowledgeRerankOutcome;
import com.insightflow.knowledge.KnowledgeRetrievalDiagnostics;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeVectorStore;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 检索诊断：候选未进 Top30、精排降权、fallback、RRF on/off 候选一致。
 */
class RagGoldRetrievalDiagnosticsComputerTest {

    private static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID VER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID GOLD = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OTHER = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private RagGoldEvidenceMatcher matcher;
    private Map<UUID, Integer> versionNumbers;

    @BeforeEach
    void setUp() {
        KnowledgeDocumentVersionRepository versions = mock(KnowledgeDocumentVersionRepository.class);
        KnowledgeDocumentVersion version = mock(KnowledgeDocumentVersion.class);
        when(version.getPublicId()).thenReturn(VER);
        when(version.getVersionNo()).thenReturn(2);
        when(versions.findByPublicIdIn(Set.of(VER))).thenReturn(List.of(version));
        matcher = new RagGoldEvidenceMatcher(versions);
        versionNumbers = Map.of(VER, 2);
    }

    @Test
    void top30OutsideGoldMeansRerankCannotSave() {
        List<RagGoldEvidenceSnapshot> evidences = List.of(
                new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.CHUNK, DOC, VER, GOLD));
        List<KnowledgeVectorStore.SearchCandidate> candidates = candidates(35, GOLD, 31);
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(1, List.of(
                new KnowledgeEvidence("knowledge:" + DOC + ":v2:" + OTHER, "t", 2, "s", "/src")));
        KnowledgeRetrievalDiagnostics diagnostics = new KnowledgeRetrievalDiagnostics(
                result, candidates, Set.of(), Set.of(), Set.of(),
                new KnowledgeRerankOutcome(candidates.subList(0, 8), "cross-encoder", "v1", 5L, false, 35));

        RagGoldRetrievalCaseDiagnostics computed = RagGoldRetrievalDiagnosticsComputer.compute(
                diagnostics, evidences, RagGoldQuestionType.SINGLE_DOCUMENT_FACT, versionNumbers, matcher);

        assertThat(computed.goldChunkRrfRank()).isEqualTo(31);
        assertThat(computed.candidateHitAt30()).isFalse();
        assertThat(computed.rerankAfterRank()).isZero();
    }

    @Test
    void rerankDemotionWhenGoldDroppedFromTop8() {
        List<RagGoldEvidenceSnapshot> evidences = List.of(
                new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.CHUNK, DOC, VER, GOLD));
        List<KnowledgeVectorStore.SearchCandidate> candidates = candidates(10, GOLD, 3);
        List<KnowledgeVectorStore.SearchCandidate> reranked = candidates(8, OTHER, 1);
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(1, reranked.stream()
                .map(candidate -> new KnowledgeEvidence(
                        "knowledge:" + candidate.documentId() + ":v2:" + candidate.chunkId(),
                        "t",
                        2,
                        "s",
                        "/src"))
                .toList());
        KnowledgeRetrievalDiagnostics diagnostics = new KnowledgeRetrievalDiagnostics(
                result, candidates, Set.of(), Set.of(), Set.of(),
                new KnowledgeRerankOutcome(reranked, "cross-encoder", "v1", 8L, false, 10));

        RagGoldRetrievalCaseDiagnostics computed = RagGoldRetrievalDiagnosticsComputer.compute(
                diagnostics, evidences, RagGoldQuestionType.SINGLE_DOCUMENT_FACT, versionNumbers, matcher);

        assertThat(computed.rerankBeforeRank()).isEqualTo(3);
        assertThat(computed.rerankAfterRank()).isZero();
    }

    @Test
    void recordsRerankFallbackMetadata() {
        List<RagGoldEvidenceSnapshot> evidences = List.of(
                new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.CHUNK, DOC, VER, GOLD));
        List<KnowledgeVectorStore.SearchCandidate> candidates = candidates(5, GOLD, 1);
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(1, List.of(
                new KnowledgeEvidence("knowledge:" + DOC + ":v2:" + GOLD, "t", 2, "s", "/src")));
        KnowledgeRetrievalDiagnostics diagnostics = new KnowledgeRetrievalDiagnostics(
                result,
                candidates,
                Set.of(),
                Set.of(),
                Set.of(),
                new KnowledgeRerankOutcome(candidates, "cross-encoder", "v1", 2L, true, 5));

        RagGoldRetrievalCaseDiagnostics computed = RagGoldRetrievalDiagnosticsComputer.compute(
                diagnostics, evidences, RagGoldQuestionType.SINGLE_DOCUMENT_FACT, versionNumbers, matcher);

        assertThat(computed.rerankFallbackUsed()).isTrue();
        assertThat(computed.rerankInputCount()).isEqualTo(5);
        assertThat(computed.rerankLatencyMs()).isEqualTo(2L);
    }

    @Test
    void recordsPerRequirementGroupFunnel() {
        UUID docB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID chunkB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<RagGoldEvidenceSnapshot> evidences = List.of(
                new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.CHUNK, DOC, VER, GOLD, "group-a"),
                new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.CHUNK, docB, VER, chunkB, "group-b"));
        List<KnowledgeVectorStore.SearchCandidate> candidates = candidates(10, GOLD, 5);
        List<KnowledgeVectorStore.SearchCandidate> reranked = candidates(8, GOLD, 2);
        reranked = new java.util.ArrayList<>(reranked);
        reranked.add(new KnowledgeVectorStore.SearchCandidate(
                docB, VER, 2, chunkB, "t", "content-b", 0.5, "SUPPORT_SOP", "section", null));
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(1, reranked.stream()
                .map(candidate -> new KnowledgeEvidence(
                        "knowledge:" + candidate.documentId() + ":v2:" + candidate.chunkId(),
                        "t", 2, "s", "/src"))
                .toList());
        KnowledgeRetrievalDiagnostics diagnostics = new KnowledgeRetrievalDiagnostics(
                result,
                candidates,
                Set.of(),
                Set.of(),
                Set.of(),
                new KnowledgeRerankOutcome(reranked, "cross-encoder", "v1", 8L, false, 10),
                List.of("子查询A", "子查询B"),
                List.of(10, 8));

        RagGoldRetrievalCaseDiagnostics computed = RagGoldRetrievalDiagnosticsComputer.compute(
                diagnostics, evidences, RagGoldQuestionType.CROSS_DOCUMENT, versionNumbers, matcher);

        assertThat(computed.requirementGroups()).hasSize(2);
        assertThat(computed.requirementGroups().get(0).groupKey()).isEqualTo("group-a");
        assertThat(computed.requirementGroups().get(0).rrfFirstRank()).isEqualTo(5);
        assertThat(computed.requirementGroups().get(0).finalFirstRank()).isEqualTo(2);
        assertThat(computed.requirementGroups().get(0).satisfiedAt8()).isTrue();
        assertThat(computed.subQueries()).containsExactly("子查询A", "子查询B");
        assertThat(computed.candidatesPerSubQuery()).containsExactly(10, 8);
    }

    private static List<KnowledgeVectorStore.SearchCandidate> candidates(int count, UUID goldChunk, int goldRank) {
        java.util.ArrayList<KnowledgeVectorStore.SearchCandidate> list = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            UUID chunkId = i == goldRank ? goldChunk : OTHER;
            list.add(new KnowledgeVectorStore.SearchCandidate(
                    DOC,
                    VER,
                    2,
                    chunkId,
                    "title",
                    "content-" + i,
                    1.0 - i * 0.01,
                    "SUPPORT_SOP",
                    "section",
                    null));
        }
        return list;
    }
}
