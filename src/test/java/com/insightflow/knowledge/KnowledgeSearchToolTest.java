package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.Workspace;
import com.insightflow.service.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RAG 检索必须始终由服务端计划驱动并保持组织/Workspace 双层隔离。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {
    @Mock private WorkspaceService workspaceService;
    @Mock private KnowledgeEmbeddingGateway embeddings;
    @Mock private KnowledgeVectorStore vectorStore;
    @Mock private Workspace workspace;

    private KnowledgeSearchTool searchTool;

    @BeforeEach
    void setUp() {
        KnowledgeRerankerSelector rerankerSelector = new KnowledgeRerankerSelector(
                new RrfOnlyKnowledgeReranker(), null, null);
        KnowledgeCrossQueryDecomposer crossQueryDecomposer = new KnowledgeCrossQueryDecomposer();
        KnowledgeTitleEntityScoreBooster titleEntityScoreBooster =
                new KnowledgeTitleEntityScoreBooster(crossQueryDecomposer);
        searchTool = new KnowledgeSearchTool(
                workspaceService,
                embeddings,
                vectorStore,
                new KnowledgeRetrievalPlanner(),
                new KnowledgeEvidenceGuardrail(),
                new KnowledgeQueryExpander(),
                rerankerSelector,
                crossQueryDecomposer,
                titleEntityScoreBooster,
                new KnowledgeCoverageAwareSelector(titleEntityScoreBooster),
                new KnowledgeIdentifierCandidateSupplement(vectorStore),
                new KnowledgeSubQueryQuotaEnforcer(titleEntityScoreBooster));
    }

    /** Phase 4A：版本标签随 identifier / subquota 开关动态拼接。 */
    @Test
    void resolveRetrievalVersionLabelReflectsAblationFlags() {
        assertThat(searchTool.resolveRetrievalVersionLabel(KnowledgeRetrievalOptions.withDecomposition(
                        false, null, null, false, false)))
                .isEqualTo("knowledge:rrf:v3+entity+coverage+gate+small2big");
        assertThat(searchTool.resolveRetrievalVersionLabel(KnowledgeRetrievalOptions.withDecomposition(
                        false, null, null, true, false)))
                .isEqualTo("knowledge:rrf:v3+entity+coverage+identifier+gate+small2big");
        assertThat(searchTool.resolveRetrievalVersionLabel(KnowledgeRetrievalOptions.withDecomposition(
                        false, null, null, false, true)))
                .isEqualTo("knowledge:rrf:v3+entity+coverage+subquota+precise+upgrade+anchor+gate+small2big");
        assertThat(searchTool.resolveRetrievalVersionLabel(KnowledgeRetrievalOptions.withDecomposition(
                        false, null, null, true, true)))
                .isEqualTo("knowledge:rrf:v3+entity+coverage+identifier+subquota+precise+upgrade+anchor+gate+small2big");
        assertThat(searchTool.resolveRetrievalVersionLabel(KnowledgeRetrievalOptions.withDecomposition(
                        false, null, null, true, true, false)))
                .isEqualTo("knowledge:rrf:v3+entity+coverage+identifier+subquota+precise+upgrade+anchor+small2big");
    }

    @Test
    void evidenceGateOffPreservesPreGateEvidenceCount() {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        KnowledgeVectorStore.SearchCandidate strongHit = new KnowledgeVectorStore.SearchCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "title",
                "content",
                0.05d,
                KnowledgeDocumentType.KNOWN_ISSUE.name(),
                "section",
                null);
        KnowledgeVectorStore.SearchCandidate weakHit = new KnowledgeVectorStore.SearchCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "weak",
                "weak content",
                0.01d,
                KnowledgeDocumentType.KNOWN_ISSUE.name(),
                "section",
                null);
        when(vectorStore.searchWithOptions(eq(3L), eq(7L), anyString(), any(), any(), any()))
                .thenReturn(new KnowledgeSearchResult(List.of(strongHit, weakHit), Set.of(), Set.of(), Set.of()))
                .thenReturn(emptySearchResult());

        KnowledgeRetrievalDiagnostics diagnostics = searchTool.retrieveWithDiagnostics(
                workspacePublicId,
                "7 月版本有哪些已知问题？",
                List.of(0.1d, 0.2d),
                KnowledgeRetrievalOptions.withDecomposition(false, null, null, false, false, false));

        assertThat(diagnostics.result().evidence()).hasSize(2);
        assertThat(diagnostics.result().gateOutcome()).isEqualTo(KnowledgeEvidenceGateDecision.OUTCOME_INJECT);
    }

    @Test
    void evidenceGateAbstainsOnWeakTopScore() {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        KnowledgeVectorStore.SearchCandidate weakHit = new KnowledgeVectorStore.SearchCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "weak",
                "weak content",
                0.01d,
                KnowledgeDocumentType.KNOWN_ISSUE.name(),
                "section",
                null);
        when(vectorStore.searchWithOptions(eq(3L), eq(7L), anyString(), any(), any(), any()))
                .thenReturn(new KnowledgeSearchResult(List.of(weakHit), Set.of(), Set.of(), Set.of()))
                .thenReturn(emptySearchResult());

        KnowledgeRetrievalDiagnostics diagnostics = searchTool.retrieveWithDiagnostics(
                workspacePublicId,
                "你好",
                List.of(0.1d, 0.2d),
                KnowledgeRetrievalOptions.withDecomposition(false, null, null, false, false, true));

        assertThat(diagnostics.result().evidence()).isEmpty();
        assertThat(diagnostics.result().abstained()).isTrue();
    }

    @Test
    void truncatesExpandedContextAtOneThousandCharacters() {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        String longBody = "段".repeat(1200);
        KnowledgeVectorStore.SearchCandidate hit = new KnowledgeVectorStore.SearchCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "title",
                longBody,
                0.05d,
                KnowledgeDocumentType.KNOWN_ISSUE.name(),
                "section",
                null);
        when(vectorStore.searchWithOptions(eq(3L), eq(7L), anyString(), any(), any(), any()))
                .thenReturn(new KnowledgeSearchResult(List.of(hit), Set.of(), Set.of(), Set.of()))
                .thenReturn(emptySearchResult());
        when(vectorStore.loadSectionChunksBatch(eq(3L), eq(7L), any()))
                .thenReturn(Map.of(hit.chunkId(), List.of(new KnowledgeSectionChunkSlice(1, longBody, "section"))));

        KnowledgeRetrievalDiagnostics diagnostics = searchTool.retrieveWithDiagnostics(
                workspacePublicId,
                "7 月版本有哪些已知问题？",
                List.of(0.1d, 0.2d),
                KnowledgeRetrievalOptions.withDecomposition(false, null, null, false, false, false));

        assertThat(diagnostics.result().evidence()).hasSize(1);
        assertThat(diagnostics.result().evidence().get(0).snippet())
                .hasSize(KnowledgeEvidenceContextExpander.EVIDENCE_CONTEXT_MAX_CHARACTERS);
    }

    @Test
    void preservesShortEvidenceSnippetWithoutPadding() {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        KnowledgeVectorStore.SearchCandidate hit = new KnowledgeVectorStore.SearchCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "title",
                "短正文",
                0.05d,
                KnowledgeDocumentType.KNOWN_ISSUE.name(),
                "section",
                null);
        when(vectorStore.searchWithOptions(eq(3L), eq(7L), anyString(), any(), any(), any()))
                .thenReturn(new KnowledgeSearchResult(List.of(hit), Set.of(), Set.of(), Set.of()))
                .thenReturn(emptySearchResult());

        KnowledgeRetrievalDiagnostics diagnostics = searchTool.retrieveWithDiagnostics(
                workspacePublicId,
                "7 月版本有哪些已知问题？",
                List.of(0.1d, 0.2d),
                KnowledgeRetrievalOptions.withDecomposition(false, null, null, false, false, false));

        assertThat(diagnostics.result().evidence().get(0).snippet()).isEqualTo("短正文");
    }

    /**
     * Planner 收窄类型后必须再跑全类型检索并合并，避免词法假阳性误判 guardrail 后漏掉 gold 文档。
     */
    @Test
    void mergesBroadSearchWhenTypesAreNarrowed() {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(embeddings.embed(any())).thenReturn(List.of(List.of(0.1d, 0.2d)));
        KnowledgeVectorStore.SearchCandidate strongHit = new KnowledgeVectorStore.SearchCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "title",
                "content",
                0.05d,
                KnowledgeDocumentType.KNOWN_ISSUE.name(),
                "section",
                null);
        when(vectorStore.searchWithOptions(eq(3L), eq(7L), anyString(), any(), any(), any()))
                .thenReturn(new KnowledgeSearchResult(List.of(strongHit), Set.of(), Set.of(), Set.of(strongHit.chunkId())))
                .thenReturn(emptySearchResult());

        KnowledgeRetrievalResult result = searchTool.retrieve(workspacePublicId, "7 月版本有哪些已知问题？");

        assertThat(result.rounds()).isEqualTo(2);
        verify(vectorStore, times(2)).searchWithOptions(eq(3L), eq(7L), anyString(), any(), any(), any());
    }

    /** 首轮无证据时最多再检索一次，第二轮不能突破当前 Workspace 的组织范围。 */
    @Test
    void performsAtMostOneSupplementalSearchInsideCurrentWorkspaceScope() {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(embeddings.embed(any())).thenReturn(List.of(List.of(0.1d, 0.2d)));
        when(vectorStore.searchWithOptions(eq(3L), eq(7L), anyString(), any(), any(), any()))
                .thenReturn(emptySearchResult());

        KnowledgeRetrievalResult result = searchTool.retrieve(workspacePublicId, "7 月版本有哪些已知问题？");

        assertThat(result.rounds()).isEqualTo(2);
        assertThat(result.evidence()).isEmpty();
        verify(vectorStore, times(2)).searchWithOptions(eq(3L), eq(7L), anyString(), any(), any(), any());
    }

    /**
     * 首轮依据问题中的“已知问题”收敛到问题文档；没有足够证据时，第二轮才放宽类型。
     * 这样既保留 Agentic RAG 的受控补检索，又避免每轮都无差别扫描全部文档类型。
     */
    @Test
    void narrowsKnownIssueQueryThenBroadensOnlyForSupplementalSearch() {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(embeddings.embed(any())).thenReturn(List.of(List.of(0.1d, 0.2d)));
        when(vectorStore.searchWithOptions(eq(3L), eq(7L), anyString(), any(), any(), any()))
                .thenReturn(emptySearchResult());

        searchTool.retrieve(workspacePublicId, "7 月已知问题有哪些？");

        InOrder calls = Mockito.inOrder(vectorStore);
        calls.verify(vectorStore)
                .searchWithOptions(
                        eq(3L),
                        eq(7L),
                        anyString(),
                        eq(List.of(KnowledgeDocumentType.KNOWN_ISSUE)),
                        any(),
                        any());
        calls.verify(vectorStore)
                .searchWithOptions(eq(3L), eq(7L), anyString(), eq(List.of()), any(), any());
    }

    private static KnowledgeSearchResult emptySearchResult() {
        return new KnowledgeSearchResult(List.of(), Set.of(), Set.of(), Set.of());
    }
}
