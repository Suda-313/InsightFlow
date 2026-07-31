package com.insightflow.evaluation.rag.gold;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagGoldCase;
import com.insightflow.entity.RagGoldCaseAssertion;
import com.insightflow.entity.RagGoldCaseEvidence;
import com.insightflow.entity.RagGoldDataset;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDatasetStatus;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.RagGoldCaseAssertionRepository;
import com.insightflow.repository.RagGoldCaseEvidenceRepository;
import com.insightflow.repository.RagGoldCaseRepository;
import com.insightflow.repository.RagGoldDatasetRepository;
import com.insightflow.service.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工金标数据集的只读加载用例，供后台 Runner 与脚本使用。
 *
 * <p>只返回已发布或冻结快照；草稿数据集对 Runner 不可见。</p>
 */
@Service
public class RagGoldDatasetReadService {

    private static final TypeReference<List<RagGoldContextTurnSnapshot>> CONTEXT_TURN_LIST_TYPE =
            new TypeReference<>() {};

    private final WorkspaceService workspaceService;
    private final RagGoldDatasetRepository datasetRepository;
    private final RagGoldCaseRepository caseRepository;
    private final RagGoldCaseEvidenceRepository evidenceRepository;
    private final RagGoldCaseAssertionRepository assertionRepository;
    private final ObjectMapper objectMapper;

    public RagGoldDatasetReadService(
            WorkspaceService workspaceService,
            RagGoldDatasetRepository datasetRepository,
            RagGoldCaseRepository caseRepository,
            RagGoldCaseEvidenceRepository evidenceRepository,
            RagGoldCaseAssertionRepository assertionRepository,
            ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.datasetRepository = datasetRepository;
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.assertionRepository = assertionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RagGoldDatasetSnapshot loadRunnableSnapshot(
            UUID workspacePublicId, String datasetKey, String datasetVersion) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        RagGoldDataset dataset = datasetRepository
                .findByWorkspaceIdAndDatasetKeyAndDatasetVersion(
                        workspace.getId(), datasetKey, datasetVersion)
                .filter(RagGoldDataset::isRunnable)
                .orElseThrow(() -> new IllegalArgumentException("可运行数据集不存在或未发布"));
        return toSnapshot(dataset, workspace.getId());
    }

    @Transactional(readOnly = true)
    public RagGoldDatasetSnapshot loadRunnableSnapshotByPublicId(
            UUID workspacePublicId, UUID datasetPublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        RagGoldDataset dataset = datasetRepository
                .findByPublicIdAndWorkspaceId(datasetPublicId, workspace.getId())
                .filter(RagGoldDataset::isRunnable)
                .orElseThrow(() -> new IllegalArgumentException("可运行数据集不存在或未发布"));
        return toSnapshot(dataset, workspace.getId());
    }

    @Transactional(readOnly = true)
    public List<RagGoldDatasetSummary> listRunnableSummaries(
            UUID workspacePublicId, RagGoldDatasetSplit split) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        List<RagGoldDataset> datasets = split == null
                ? listAllRunnable(workspace.getId())
                : listPublishedOrFrozenBySplit(workspace.getId(), split);
        return datasets.stream().map(this::toSummary).toList();
    }

    private List<RagGoldDataset> listAllRunnable(Long workspaceId) {
        List<RagGoldDataset> published = datasetRepository.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(
                workspaceId, RagGoldDatasetStatus.PUBLISHED);
        List<RagGoldDataset> frozen = datasetRepository.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(
                workspaceId, RagGoldDatasetStatus.FROZEN);
        List<RagGoldDataset> combined = new ArrayList<>(published.size() + frozen.size());
        combined.addAll(published);
        combined.addAll(frozen);
        return combined;
    }

    private List<RagGoldDataset> listPublishedOrFrozenBySplit(Long workspaceId, RagGoldDatasetSplit split) {
        List<RagGoldDataset> published = datasetRepository.findByWorkspaceIdAndStatusAndSplitOrderByCreatedAtDesc(
                workspaceId, RagGoldDatasetStatus.PUBLISHED, split);
        List<RagGoldDataset> frozen = datasetRepository.findByWorkspaceIdAndStatusAndSplitOrderByCreatedAtDesc(
                workspaceId, RagGoldDatasetStatus.FROZEN, split);
        List<RagGoldDataset> combined = new ArrayList<>(published.size() + frozen.size());
        combined.addAll(published);
        combined.addAll(frozen);
        return combined;
    }

    private RagGoldDatasetSnapshot toSnapshot(RagGoldDataset dataset, Long workspaceId) {
        List<RagGoldCase> cases = caseRepository.findByDatasetIdAndWorkspaceIdOrderBySortOrderAscCaseKeyAsc(
                dataset.getId(), workspaceId);
        List<Long> caseIds = cases.stream().map(RagGoldCase::getId).toList();
        Map<Long, List<RagGoldCaseEvidence>> evidencesByCase = evidenceRepository
                .findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(caseIds, workspaceId)
                .stream()
                .collect(Collectors.groupingBy(RagGoldCaseEvidence::getCaseId));
        Map<Long, List<RagGoldCaseAssertion>> assertionsByCase = assertionRepository
                .findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(caseIds, workspaceId)
                .stream()
                .collect(Collectors.groupingBy(RagGoldCaseAssertion::getCaseId));
        List<RagGoldCaseSnapshot> caseSnapshots = cases.stream()
                .map(goldCase -> toCaseSnapshot(
                        goldCase,
                        evidencesByCase.getOrDefault(goldCase.getId(), List.of()),
                        assertionsByCase.getOrDefault(goldCase.getId(), List.of())))
                .toList();
        return new RagGoldDatasetSnapshot(
                dataset.getPublicId(),
                dataset.getDatasetKey(),
                dataset.getDatasetVersion(),
                dataset.getSplit(),
                dataset.getStatus(),
                dataset.getSourceCorpusVersion(),
                dataset.getChecksum(),
                dataset.getPublishedAt(),
                dataset.getFrozenAt(),
                caseSnapshots);
    }

    private RagGoldCaseSnapshot toCaseSnapshot(
            RagGoldCase goldCase,
            List<RagGoldCaseEvidence> evidences,
            List<RagGoldCaseAssertion> assertions) {
        List<RagGoldEvidenceSnapshot> evidenceSnapshots = evidences.stream()
                .map(evidence -> new RagGoldEvidenceSnapshot(
                        evidence.getGranularity(),
                        evidence.getDocumentPublicId(),
                        evidence.getVersionPublicId(),
                        evidence.getChunkPublicId(),
                        evidence.getRequirementKey()))
                .toList();
        List<RagGoldAssertionSnapshot> assertionSnapshots = assertions.stream()
                .map(assertion -> new RagGoldAssertionSnapshot(
                        assertion.getAssertionType(),
                        assertion.getAssertionText(),
                        assertion.getWeight()))
                .toList();
        return new RagGoldCaseSnapshot(
                goldCase.getPublicId(),
                goldCase.getCaseKey(),
                goldCase.getQuestionText(),
                goldCase.getQuestionType(),
                goldCase.getDifficulty(),
                goldCase.isShouldRefuse(),
                goldCase.getAnnotationBasis(),
                goldCase.getReviewer(),
                evidenceSnapshots,
                assertionSnapshots,
                parseContextTurns(goldCase.getContextTurnsJson()));
    }

    private List<RagGoldContextTurnSnapshot> parseContextTurns(String contextTurnsJson) {
        if (contextTurnsJson == null || contextTurnsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(contextTurnsJson, CONTEXT_TURN_LIST_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析 context_turns JSON", exception);
        }
    }

    private RagGoldDatasetSummary toSummary(RagGoldDataset dataset) {
        return new RagGoldDatasetSummary(
                dataset.getPublicId(),
                dataset.getDatasetKey(),
                dataset.getDatasetVersion(),
                dataset.getSplit(),
                dataset.getStatus(),
                dataset.getSourceCorpusVersion(),
                dataset.getChecksum(),
                dataset.getPublishedAt(),
                dataset.getFrozenAt());
    }

    /** 列表视图用的数据集摘要，不含题目正文。 */
    public record RagGoldDatasetSummary(
            UUID datasetPublicId,
            String datasetKey,
            String datasetVersion,
            RagGoldDatasetSplit split,
            RagGoldDatasetStatus status,
            String sourceCorpusVersion,
            String checksum,
            java.time.OffsetDateTime publishedAt,
            java.time.OffsetDateTime frozenAt) {
    }
}
