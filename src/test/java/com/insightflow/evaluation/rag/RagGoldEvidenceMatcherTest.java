package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 文档级 Recall 与 chunk 精确匹配解耦：同文档不同 chunk 仍应计为 document 命中。
 */
class RagGoldEvidenceMatcherTest {

    private static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID VER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID EXPECTED_CHUNK = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OTHER_CHUNK = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

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
    void matchesDocumentWhenRuntimeIdIsDifferentChunkOfSameDocument() {
        RagGoldEvidenceSnapshot chunkEvidence = new RagGoldEvidenceSnapshot(
                RagGoldEvidenceGranularity.CHUNK, DOC, VER, EXPECTED_CHUNK);
        String runtimeId = "knowledge:" + DOC + ":v2:" + OTHER_CHUNK;

        assertThat(matcher.matchesDocument(chunkEvidence, runtimeId)).isTrue();
        assertThat(matcher.matchesEvidence(chunkEvidence, runtimeId, versionNumbers)).isFalse();
    }

    @Test
    void documentRecallWithinTopKWhenWrongChunkRetrieved() {
        RagGoldEvidenceSnapshot chunkEvidence = new RagGoldEvidenceSnapshot(
                RagGoldEvidenceGranularity.CHUNK, DOC, VER, EXPECTED_CHUNK);
        List<String> ranked = List.of("knowledge:" + DOC + ":v2:" + OTHER_CHUNK);

        assertThat(matcher.hitWithinTopK(
                        List.of(chunkEvidence), ranked, 8, versionNumbers, RagGoldEvidenceGranularity.DOCUMENT))
                .isTrue();
        assertThat(matcher.hitWithinTopK(
                        List.of(chunkEvidence), ranked, 8, versionNumbers, RagGoldEvidenceGranularity.CHUNK))
                .isFalse();
    }
}
