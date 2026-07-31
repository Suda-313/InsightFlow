package com.insightflow.evaluation.rag.gold;

import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.entity.RagGoldCase;
import com.insightflow.entity.RagGoldCaseAssertion;
import com.insightflow.entity.RagGoldCaseEvidence;
import com.insightflow.entity.RagGoldDataset;
import com.insightflow.entity.RagGoldDatasetSplit;
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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工金标数据集的后台写入用例：草稿创建、题目追加、发布与冻结。
 *
 * <p>不提供 HTTP 入口；供导入脚本或内部 seed 使用。发布/冻结后内容不可变。</p>
 */
@Service
public class RagGoldDatasetCommandService {

    private final WorkspaceService workspaceService;
    private final RagGoldDatasetRepository datasetRepository;
    private final RagGoldCaseRepository caseRepository;
    private final RagGoldCaseEvidenceRepository evidenceRepository;
    private final RagGoldCaseAssertionRepository assertionRepository;

    public RagGoldDatasetCommandService(
            WorkspaceService workspaceService,
            RagGoldDatasetRepository datasetRepository,
            RagGoldCaseRepository caseRepository,
            RagGoldCaseEvidenceRepository evidenceRepository,
            RagGoldCaseAssertionRepository assertionRepository) {
        this.workspaceService = workspaceService;
        this.datasetRepository = datasetRepository;
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.assertionRepository = assertionRepository;
    }

    @Transactional
    public RagGoldDataset createDraft(
            UUID workspacePublicId,
            String datasetKey,
            String datasetVersion,
            RagGoldDatasetSplit split,
            String sourceCorpusVersion) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        datasetRepository.findByWorkspaceIdAndDatasetKeyAndDatasetVersion(
                        workspace.getId(), datasetKey, datasetVersion)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("dataset_key 与 dataset_version 已存在");
                });
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                workspace.getId(),
                workspace.getOrganizationId(),
                datasetKey,
                datasetVersion,
                split,
                sourceCorpusVersion);
        return datasetRepository.save(dataset);
    }

    @Transactional
    public RagGoldCase addCase(UUID workspacePublicId, UUID datasetPublicId, RagGoldCaseDraft draft) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        RagGoldDataset dataset = requireMutableDataset(workspace.getId(), datasetPublicId);
        if (caseRepository.existsByDatasetIdAndCaseKey(dataset.getId(), draft.caseKey())) {
            throw new IllegalArgumentException("case_key 已存在: " + draft.caseKey());
        }
        RagGoldCase goldCase = RagGoldCase.create(
                workspace.getId(),
                dataset.getId(),
                draft.caseKey(),
                draft.questionText(),
                draft.questionType(),
                draft.difficulty(),
                draft.shouldRefuse(),
                draft.annotationBasis(),
                draft.reviewer(),
                draft.sortOrder(),
                draft.contextTurnsJson());
        RagGoldCase savedCase = caseRepository.save(goldCase);
        int evidenceOrder = 0;
        for (RagGoldEvidenceDraft evidenceDraft : draft.evidences()) {
            evidenceRepository.save(RagGoldCaseEvidence.create(
                    workspace.getId(),
                    savedCase.getId(),
                    evidenceDraft.granularity(),
                    evidenceDraft.documentPublicId(),
                    evidenceDraft.versionPublicId(),
                    evidenceDraft.chunkPublicId(),
                    evidenceOrder++,
                    evidenceDraft.requirementKey()));
        }
        int assertionOrder = 0;
        for (RagGoldAssertionDraft assertionDraft : draft.assertions()) {
            assertionRepository.save(RagGoldCaseAssertion.create(
                    workspace.getId(),
                    savedCase.getId(),
                    assertionDraft.assertionType(),
                    assertionDraft.assertionText(),
                    assertionDraft.weight(),
                    assertionOrder++));
        }
        return savedCase;
    }

    @Transactional
    public RagGoldDataset publish(UUID workspacePublicId, UUID datasetPublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        RagGoldDataset dataset = requireMutableDataset(workspace.getId(), datasetPublicId);
        List<RagGoldCase> cases = caseRepository.findByDatasetIdAndWorkspaceIdOrderBySortOrderAscCaseKeyAsc(
                dataset.getId(), workspace.getId());
        if (cases.isEmpty()) {
            throw new IllegalStateException("空数据集不能发布");
        }
        List<Long> caseIds = cases.stream().map(RagGoldCase::getId).toList();
        List<RagGoldCaseEvidence> evidences =
                evidenceRepository.findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(
                        caseIds, workspace.getId());
        List<RagGoldCaseAssertion> assertions =
                assertionRepository.findByCaseIdInAndWorkspaceIdOrderByCaseIdAscSortOrderAsc(
                        caseIds, workspace.getId());
        String checksum = RagGoldDatasetChecksum.compute(dataset, cases, evidences, assertions);
        dataset.publish(checksum);
        return datasetRepository.save(dataset);
    }

    @Transactional
    public RagGoldDataset freeze(UUID workspacePublicId, UUID datasetPublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        RagGoldDataset dataset = requireDataset(workspace.getId(), datasetPublicId);
        dataset.freeze();
        return datasetRepository.save(dataset);
    }

    private RagGoldDataset requireMutableDataset(Long workspaceId, UUID datasetPublicId) {
        RagGoldDataset dataset = requireDataset(workspaceId, datasetPublicId);
        if (!dataset.isMutable()) {
            throw new IllegalStateException("已发布或冻结的数据集不可修改");
        }
        return dataset;
    }

    private RagGoldDataset requireDataset(Long workspaceId, UUID datasetPublicId) {
        return datasetRepository.findByPublicIdAndWorkspaceId(datasetPublicId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("数据集不存在或不属于当前 Workspace"));
    }

    /** 导入脚本使用的题目草稿。 */
    public record RagGoldCaseDraft(
            String caseKey,
            String questionText,
            RagGoldQuestionType questionType,
            RagGoldDifficulty difficulty,
            boolean shouldRefuse,
            String annotationBasis,
            String reviewer,
            int sortOrder,
            List<RagGoldEvidenceDraft> evidences,
            List<RagGoldAssertionDraft> assertions,
            String contextTurnsJson) {

        /** 兼容无多轮上下文的草稿构造。 */
        public RagGoldCaseDraft(
                String caseKey,
                String questionText,
                RagGoldQuestionType questionType,
                RagGoldDifficulty difficulty,
                boolean shouldRefuse,
                String annotationBasis,
                String reviewer,
                int sortOrder,
                List<RagGoldEvidenceDraft> evidences,
                List<RagGoldAssertionDraft> assertions) {
            this(
                    caseKey,
                    questionText,
                    questionType,
                    difficulty,
                    shouldRefuse,
                    annotationBasis,
                    reviewer,
                    sortOrder,
                    evidences,
                    assertions,
                    null);
        }
    }

    /** 单条可接受证据草稿。 */
    public record RagGoldEvidenceDraft(
            RagGoldEvidenceGranularity granularity,
            UUID documentPublicId,
            UUID versionPublicId,
            UUID chunkPublicId,
            String requirementKey) {

        public RagGoldEvidenceDraft(
                RagGoldEvidenceGranularity granularity,
                UUID documentPublicId,
                UUID versionPublicId,
                UUID chunkPublicId) {
            this(granularity, documentPublicId, versionPublicId, chunkPublicId, null);
        }
    }

    /** 单条断言草稿。 */
    public record RagGoldAssertionDraft(
            RagGoldAssertionType assertionType,
            String assertionText,
            double weight) {
    }
}
