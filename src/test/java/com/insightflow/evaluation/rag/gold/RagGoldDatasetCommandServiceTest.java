package com.insightflow.evaluation.rag.gold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.RagGoldCase;
import com.insightflow.entity.RagGoldDataset;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDatasetStatus;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 后台写入用例：草稿追加、发布 checksum 与不可变约束。 */
class RagGoldDatasetCommandServiceTest {

    private static final UUID WORKSPACE_PUBLIC_ID = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final UUID VER = UUID.randomUUID();
    private static final UUID CHUNK = UUID.randomUUID();

    private WorkspaceService workspaceService;
    private RagGoldDatasetRepository datasetRepository;
    private RagGoldCaseRepository caseRepository;
    private RagGoldCaseEvidenceRepository evidenceRepository;
    private RagGoldCaseAssertionRepository assertionRepository;
    private RagGoldDatasetCommandService service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        datasetRepository = mock(RagGoldDatasetRepository.class);
        caseRepository = mock(RagGoldCaseRepository.class);
        evidenceRepository = mock(RagGoldCaseEvidenceRepository.class);
        assertionRepository = mock(RagGoldCaseAssertionRepository.class);
        service = new RagGoldDatasetCommandService(
                workspaceService, datasetRepository, caseRepository, evidenceRepository, assertionRepository);
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(7L);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(workspaceService.get(WORKSPACE_PUBLIC_ID)).thenReturn(workspace);
    }

    @Test
    void publishesDraftWithChecksum() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");
        RagGoldDataset spiedDataset = spy(dataset);
        doReturn(99L).when(spiedDataset).getId();
        UUID datasetPublicId = spiedDataset.getPublicId();
        RagGoldCase goldCase = mock(RagGoldCase.class);
        when(goldCase.getId()).thenReturn(10L);
        when(datasetRepository.findByPublicIdAndWorkspaceId(datasetPublicId, 7L))
                .thenReturn(Optional.of(spiedDataset));
        when(caseRepository.findByDatasetIdAndWorkspaceIdOrderBySortOrderAscCaseKeyAsc(any(), eq(7L)))
                .thenReturn(List.of(goldCase));
        when(evidenceRepository.findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(any(), any()))
                .thenReturn(List.of());
        when(assertionRepository.findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(any(), any()))
                .thenReturn(List.of());
        when(datasetRepository.save(any(RagGoldDataset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RagGoldDataset published = service.publish(WORKSPACE_PUBLIC_ID, datasetPublicId);

        assertThat(published.getStatus()).isEqualTo(RagGoldDatasetStatus.PUBLISHED);
        assertThat(published.getChecksum()).isNotBlank();
    }

    @Test
    void rejectsMutationOnPublishedDataset() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");
        dataset.publish("abc123");
        when(datasetRepository.findByPublicIdAndWorkspaceId(dataset.getPublicId(), 7L))
                .thenReturn(Optional.of(dataset));

        assertThatThrownBy(() -> service.addCase(
                        WORKSPACE_PUBLIC_ID,
                        dataset.getPublicId(),
                        sampleDraft()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可修改");
    }

    @Test
    void addCasePersistsEvidenceAndAssertions() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");
        RagGoldDataset spiedDataset = spy(dataset);
        doReturn(99L).when(spiedDataset).getId();
        when(datasetRepository.findByPublicIdAndWorkspaceId(spiedDataset.getPublicId(), 7L))
                .thenReturn(Optional.of(spiedDataset));
        when(caseRepository.existsByDatasetIdAndCaseKey(99L, "case-1")).thenReturn(false);
        when(caseRepository.save(any(RagGoldCase.class))).thenAnswer(invocation -> {
            RagGoldCase input = invocation.getArgument(0);
            RagGoldCase persisted = mock(RagGoldCase.class);
            when(persisted.getId()).thenReturn(10L);
            when(persisted.getCaseKey()).thenReturn(input.getCaseKey());
            return persisted;
        });

        service.addCase(WORKSPACE_PUBLIC_ID, spiedDataset.getPublicId(), sampleDraft());

        verify(evidenceRepository).save(any());
        verify(assertionRepository).save(any());
    }

    @Test
    void createDraftUsesWorkspaceOrganizationId() {
        when(datasetRepository.findByWorkspaceIdAndDatasetKeyAndDatasetVersion(7L, "ops-rag", "v1"))
                .thenReturn(Optional.empty());
        when(datasetRepository.save(any(RagGoldDataset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RagGoldDataset created = service.createDraft(
                WORKSPACE_PUBLIC_ID, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");

        ArgumentCaptor<RagGoldDataset> captor = ArgumentCaptor.forClass(RagGoldDataset.class);
        verify(datasetRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(3L);
        assertThat(created.getWorkspaceId()).isEqualTo(7L);
    }

    private RagGoldDatasetCommandService.RagGoldCaseDraft sampleDraft() {
        return new RagGoldDatasetCommandService.RagGoldCaseDraft(
                "case-1",
                "问题",
                RagGoldQuestionType.SINGLE_DOCUMENT_FACT,
                RagGoldDifficulty.EASY,
                false,
                "basis",
                "reviewer",
                0,
                List.of(new RagGoldDatasetCommandService.RagGoldEvidenceDraft(
                        RagGoldEvidenceGranularity.CHUNK, DOC, VER, CHUNK)),
                List.of(new RagGoldDatasetCommandService.RagGoldAssertionDraft(
                        RagGoldAssertionType.REQUIRED_FACT, "关键事实", 1.0)));
    }
}
