package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Phase 2：标识符补召回并入 Candidate 池。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIdentifierCandidateSupplementTest {

    @Mock private KnowledgeVectorStore vectorStore;

    private KnowledgeIdentifierCandidateSupplement supplement;

    @BeforeEach
    void setUp() {
        supplement = new KnowledgeIdentifierCandidateSupplement(vectorStore);
    }

    @Test
    void searchesMissingIdentifierAndMergesIntoPool() {
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        org.mockito.Mockito.when(workspace.getId()).thenReturn(7L);
        org.mockito.Mockito.when(workspace.getOrganizationId()).thenReturn(1L);
        UUID kiChunk = UUID.randomUUID();
        KnowledgeSearchResult merged = new KnowledgeSearchResult(
                List.of(candidate(UUID.randomUUID(), "其他文档", "无编号", 0.05)),
                Set.of(),
                Set.of(),
                Set.of());
        KnowledgeSearchResult kiHits = new KnowledgeSearchResult(
                List.of(candidate(kiChunk, "1.3.1 热修", "KI-1301 机关交互", 0.12)),
                Set.of(kiChunk),
                Set.of(),
                Set.of());

        when(vectorStore.searchByExactIdentifier(anyLong(), anyLong(), eq("KI-1301"), anyInt(), anyDouble()))
                .thenReturn(kiHits);
        when(vectorStore.searchByExactIdentifier(anyLong(), anyLong(), eq("KI-1405"), anyInt(), anyDouble()))
                .thenReturn(new KnowledgeSearchResult(List.of(), Set.of(), Set.of(), Set.of()));

        KnowledgeSearchResult result = supplement.supplement(
                workspace, "KI-1301 和 KI-1405 是否同根因？", merged);

        assertThat(result.candidates()).anyMatch(item -> item.content().contains("KI-1301"));
        verify(vectorStore).searchByExactIdentifier(
                eq(1L), eq(7L), eq("KI-1301"),
                eq(KnowledgeIdentifierCandidateSupplement.PER_IDENTIFIER_LIMIT),
                eq(KnowledgeIdentifierCandidateSupplement.SUPPLEMENT_SCORE));
        verify(vectorStore).searchByExactIdentifier(
                eq(1L), eq(7L), eq("KI-1405"),
                eq(KnowledgeIdentifierCandidateSupplement.PER_IDENTIFIER_LIMIT),
                eq(KnowledgeIdentifierCandidateSupplement.SUPPLEMENT_SCORE));
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(
            UUID documentId, String title, String content, double score) {
        return new KnowledgeVectorStore.SearchCandidate(
                documentId,
                UUID.randomUUID(),
                5,
                UUID.randomUUID(),
                title,
                content,
                score,
                "RELEASE_NOTE",
                "section",
                null);
    }
}
