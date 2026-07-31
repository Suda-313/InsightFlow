package com.insightflow.evaluation.rag.gold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.RagGoldCase;
import com.insightflow.entity.RagGoldCaseAssertion;
import com.insightflow.entity.RagGoldCaseEvidence;
import com.insightflow.entity.RagGoldDataset;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.RagGoldCaseAssertionRepository;
import com.insightflow.repository.RagGoldCaseEvidenceRepository;
import com.insightflow.repository.RagGoldCaseRepository;
import com.insightflow.repository.RagGoldDatasetRepository;
import com.insightflow.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 只读加载：Workspace 隔离、草稿不可见、多证据集映射。 */
class RagGoldDatasetReadServiceTest {

    private static final UUID WORKSPACE_PUBLIC_ID = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final UUID VER = UUID.randomUUID();
    private static final UUID CHUNK = UUID.randomUUID();

    private WorkspaceService workspaceService;
    private RagGoldDatasetRepository datasetRepository;
    private RagGoldCaseRepository caseRepository;
    private RagGoldCaseEvidenceRepository evidenceRepository;
    private RagGoldCaseAssertionRepository assertionRepository;
    private RagGoldDatasetReadService service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        datasetRepository = mock(RagGoldDatasetRepository.class);
        caseRepository = mock(RagGoldCaseRepository.class);
        evidenceRepository = mock(RagGoldCaseEvidenceRepository.class);
        assertionRepository = mock(RagGoldCaseAssertionRepository.class);
        service = new RagGoldDatasetReadService(
                workspaceService,
                datasetRepository,
                caseRepository,
                evidenceRepository,
                assertionRepository,
                new ObjectMapper());
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(7L);
        when(workspaceService.get(WORKSPACE_PUBLIC_ID)).thenReturn(workspace);
    }

    @Test
    void loadsPublishedSnapshotWithEvidenceAndAssertions() {
        RagGoldDataset dataset = publishedDataset();
        RagGoldCase goldCase = mock(RagGoldCase.class);
        when(goldCase.getId()).thenReturn(10L);
        when(goldCase.getPublicId()).thenReturn(UUID.randomUUID());
        when(goldCase.getCaseKey()).thenReturn("case-1");
        when(goldCase.getQuestionText()).thenReturn("问题");
        when(goldCase.getQuestionType()).thenReturn(RagGoldQuestionType.SINGLE_DOCUMENT_FACT);
        when(goldCase.getDifficulty()).thenReturn(RagGoldDifficulty.EASY);
        when(goldCase.isShouldRefuse()).thenReturn(false);
        when(goldCase.getAnnotationBasis()).thenReturn("basis");
        when(goldCase.getReviewer()).thenReturn("reviewer");
        when(goldCase.getContextTurnsJson()).thenReturn(null);
        RagGoldCaseEvidence evidence = RagGoldCaseEvidence.create(
                7L, 10L, RagGoldEvidenceGranularity.CHUNK, DOC, VER, CHUNK, 0);
        RagGoldCaseAssertion assertion = RagGoldCaseAssertion.create(
                7L, 10L, RagGoldAssertionType.REQUIRED_FACT, "关键事实", 1.0, 0);
        when(datasetRepository.findByWorkspaceIdAndDatasetKeyAndDatasetVersion(7L, "ops-rag", "v1"))
                .thenReturn(Optional.of(dataset));
        when(caseRepository.findByDatasetIdAndWorkspaceIdOrderBySortOrderAscCaseKeyAsc(any(), eq(7L)))
                .thenReturn(List.of(goldCase));
        when(evidenceRepository.findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(anyList(), eq(7L)))
                .thenReturn(List.of(evidence));
        when(assertionRepository.findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(anyList(), eq(7L)))
                .thenReturn(List.of(assertion));

        RagGoldDatasetSnapshot snapshot = service.loadRunnableSnapshot(WORKSPACE_PUBLIC_ID, "ops-rag", "v1");

        assertThat(snapshot.datasetKey()).isEqualTo("ops-rag");
        assertThat(snapshot.cases()).hasSize(1);
        assertThat(snapshot.cases().get(0).evidences()).hasSize(1);
        assertThat(snapshot.cases().get(0).assertions()).hasSize(1);
        assertThat(snapshot.cases().get(0).evidences().get(0).chunkPublicId()).isEqualTo(CHUNK);
    }

    @Test
    void hidesDraftDatasetFromRunner() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");
        when(datasetRepository.findByWorkspaceIdAndDatasetKeyAndDatasetVersion(7L, "ops-rag", "v1"))
                .thenReturn(Optional.of(dataset));

        assertThatThrownBy(() -> service.loadRunnableSnapshot(WORKSPACE_PUBLIC_ID, "ops-rag", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未发布");
    }

    @Test
    void rejectsCrossWorkspaceLookup() {
        when(datasetRepository.findByWorkspaceIdAndDatasetKeyAndDatasetVersion(7L, "ops-rag", "v1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadRunnableSnapshot(WORKSPACE_PUBLIC_ID, "ops-rag", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RagGoldDataset publishedDataset() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");
        dataset.publish("abc123");
        RagGoldDataset spy = org.mockito.Mockito.spy(dataset);
        doReturn(99L).when(spy).getId();
        return spy;
    }
}
