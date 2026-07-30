package com.insightflow.evaluation.rag.gold.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagGoldDataset;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDatasetStatus;
import com.insightflow.evaluation.rag.gold.RagGoldDatasetReadService;
import com.insightflow.evaluation.rag.gold.RagGoldDatasetSnapshot;
import com.insightflow.repository.RagGoldCaseAssertionRepository;
import com.insightflow.repository.RagGoldCaseEvidenceRepository;
import com.insightflow.repository.RagGoldCaseRepository;
import com.insightflow.repository.RagGoldDatasetRepository;
import com.insightflow.service.WorkspaceService;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 模拟导入后 ReadService 对三份 split 的可加载性；frozen 集 status=FROZEN 且 isRunnable。
 *
 * <p>真实 DB 导入后可用 {@code loadRunnableSnapshot(workspace, ops-rag-v1, dev-240)} 做冒烟。</p>
 */
class RagGoldSeedReadServiceContractTest {

    private static final UUID WORKSPACE = UUID.fromString("1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668");
    private static final Path MANIFEST = Path.of("evaluation", "rag", "gold", "corpus-manifest.json");

    private RagGoldDatasetRepository datasetRepository;
    private RagGoldCaseRepository caseRepository;
    private RagGoldCaseEvidenceRepository evidenceRepository;
    private RagGoldCaseAssertionRepository assertionRepository;
    private RagGoldDatasetReadService readService;

    @BeforeEach
    void setUp() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        datasetRepository = mock(RagGoldDatasetRepository.class);
        caseRepository = mock(RagGoldCaseRepository.class);
        evidenceRepository = mock(RagGoldCaseEvidenceRepository.class);
        assertionRepository = mock(RagGoldCaseAssertionRepository.class);
        readService = new RagGoldDatasetReadService(
                workspaceService, datasetRepository, caseRepository, evidenceRepository, assertionRepository);
        var workspace = mock(com.insightflow.entity.Workspace.class);
        when(workspace.getId()).thenReturn(7L);
        when(workspaceService.get(WORKSPACE)).thenReturn(workspace);
        when(caseRepository.findByDatasetIdAndWorkspaceIdOrderBySortOrderAscCaseKeyAsc(any(), eq(7L)))
                .thenReturn(List.of());
        when(evidenceRepository.findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(any(), eq(7L)))
                .thenReturn(List.of());
        when(assertionRepository.findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(any(), eq(7L)))
                .thenReturn(List.of());
    }

    @Test
    void readServiceLoadsAllThreeSplitsAfterPublish() throws Exception {
        assertRunnableSnapshot("dev-240", RagGoldDatasetSplit.DEVELOPMENT, RagGoldDatasetStatus.PUBLISHED, 240);
        assertRunnableSnapshot("val-80", RagGoldDatasetSplit.VALIDATION, RagGoldDatasetStatus.PUBLISHED, 80);
        assertRunnableSnapshot("frozen-80", RagGoldDatasetSplit.FROZEN, RagGoldDatasetStatus.FROZEN, 80);
    }

    @Test
    void seedImporterProducesDraftCountMatchingSeedFile() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RagGoldSeedValidator validator = new RagGoldSeedValidator(
                mapper, new RagGoldCorpusManifestResolver(MANIFEST, mapper));
        RagGoldSeedFile seed = validator.validateAndParse(
                Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-dev-240.json"));
        assertThat(seed.cases()).hasSize(240);
        assertThat(seed.datasetKey()).isEqualTo("ops-rag-v1");
    }

    private void assertRunnableSnapshot(
            String datasetVersion,
            RagGoldDatasetSplit split,
            RagGoldDatasetStatus status,
            int ignoredExpectedCases) {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag-v1", datasetVersion, split, "corpus:chaoziran-2026-07-published");
        dataset.publish("checksum-placeholder");
        if (status == RagGoldDatasetStatus.FROZEN) {
            dataset.freeze();
        }
        RagGoldDataset spied = spy(dataset);
        doReturn(100L).when(spied).getId();
        when(datasetRepository.findByWorkspaceIdAndDatasetKeyAndDatasetVersion(7L, "ops-rag-v1", datasetVersion))
                .thenReturn(Optional.of(spied));

        RagGoldDatasetSnapshot snapshot = readService.loadRunnableSnapshot(WORKSPACE, "ops-rag-v1", datasetVersion);

        assertThat(snapshot.split()).isEqualTo(split);
        assertThat(snapshot.status()).isEqualTo(status);
        assertThat(spied.isRunnable()).isTrue();
        assertThat(snapshot.datasetVersion()).isEqualTo(datasetVersion);
    }
}
