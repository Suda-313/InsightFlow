package com.insightflow.evaluation.rag.gold.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.entity.RagGoldDataset;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldDatasetCommandService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将校验通过的 seed 文件写入 {@link RagGoldDatasetCommandService}：草稿 → 批量追加 → 发布（冻结集再 freeze）。
 */
@Service
public class RagGoldSeedImporter {

    private static final Logger log = LoggerFactory.getLogger(RagGoldSeedImporter.class);

    private static final TypeReference<List<RagGoldSeedFile.ContextTurn>> CONTEXT_TURN_LIST_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final RagGoldDatasetCommandService commandService;

    public RagGoldSeedImporter(ObjectMapper objectMapper, RagGoldDatasetCommandService commandService) {
        this.objectMapper = objectMapper;
        this.commandService = commandService;
    }

    /** 导入单个 seed 文件；返回已发布（或冻结）数据集的 public_id 与 checksum。 */
    @Transactional
    public ImportResult importSeed(Path seedPath, Path manifestPath) throws IOException {
        RagGoldCorpusManifestResolver resolver = new RagGoldCorpusManifestResolver(manifestPath, objectMapper);
        RagGoldSeedValidator validator = new RagGoldSeedValidator(objectMapper, resolver);
        RagGoldSeedFile seed = validator.validateAndParse(seedPath);

        log.info(
                "Importing RAG gold dataset {}:{} ({} cases, split={})",
                seed.datasetKey(),
                seed.datasetVersion(),
                seed.cases().size(),
                seed.split());

        RagGoldDataset dataset = commandService.createDraft(
                seed.workspacePublicId(),
                seed.datasetKey(),
                seed.datasetVersion(),
                RagGoldDatasetSplit.valueOf(seed.split()),
                seed.sourceCorpusVersion());

        for (RagGoldSeedFile.CaseSeed goldCase : seed.cases()) {
            commandService.addCase(seed.workspacePublicId(), dataset.getPublicId(), toDraft(goldCase, resolver));
        }

        RagGoldDataset published = commandService.publish(seed.workspacePublicId(), dataset.getPublicId());
        if (published.getSplit() == RagGoldDatasetSplit.FROZEN) {
            published = commandService.freeze(seed.workspacePublicId(), published.getPublicId());
        }

        log.info(
                "Imported RAG gold dataset {}:{} public_id={} checksum={} status={}",
                published.getDatasetKey(),
                published.getDatasetVersion(),
                published.getPublicId(),
                published.getChecksum(),
                published.getStatus());

        return new ImportResult(
                published.getPublicId(),
                published.getDatasetKey(),
                published.getDatasetVersion(),
                published.getSplit(),
                published.getStatus(),
                published.getChecksum(),
                seed.cases().size());
    }

    private RagGoldDatasetCommandService.RagGoldCaseDraft toDraft(
            RagGoldSeedFile.CaseSeed goldCase, RagGoldCorpusManifestResolver resolver) {
        List<RagGoldDatasetCommandService.RagGoldEvidenceDraft> evidences = new ArrayList<>();
        for (RagGoldSeedFile.EvidenceSeed evidenceSeed : goldCase.evidences()) {
            RagGoldCorpusManifestResolver.ResolvedEvidence resolved =
                    resolver.resolve(goldCase.caseKey(), evidenceSeed);
            evidences.add(new RagGoldDatasetCommandService.RagGoldEvidenceDraft(
                    RagGoldEvidenceGranularity.valueOf(evidenceSeed.granularity()),
                    resolved.documentPublicId(),
                    resolved.versionPublicId(),
                    resolved.chunkPublicId(),
                    evidenceSeed.requirementKey()));
        }
        List<RagGoldDatasetCommandService.RagGoldAssertionDraft> assertions = new ArrayList<>();
        for (RagGoldSeedFile.AssertionSeed assertionSeed : goldCase.assertions()) {
            assertions.add(new RagGoldDatasetCommandService.RagGoldAssertionDraft(
                    RagGoldAssertionType.valueOf(assertionSeed.assertionType()),
                    assertionSeed.assertionText(),
                    assertionSeed.weight()));
        }
        return new RagGoldDatasetCommandService.RagGoldCaseDraft(
                goldCase.caseKey(),
                goldCase.questionText(),
                RagGoldQuestionType.valueOf(goldCase.questionType()),
                RagGoldDifficulty.valueOf(goldCase.difficulty()),
                goldCase.shouldRefuse(),
                goldCase.annotationBasis(),
                goldCase.reviewer(),
                goldCase.sortOrder(),
                evidences,
                assertions,
                serializeContextTurns(goldCase.contextTurns()));
    }

    private String serializeContextTurns(List<RagGoldSeedFile.ContextTurn> contextTurns) {
        if (contextTurns == null || contextTurns.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(contextTurns);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 context_turns", exception);
        }
    }

    /** 导入完成后返回给脚本/Runner 的摘要。 */
    public record ImportResult(
            UUID datasetPublicId,
            String datasetKey,
            String datasetVersion,
            RagGoldDatasetSplit split,
            com.insightflow.entity.RagGoldDatasetStatus status,
            String checksum,
            int caseCount) {
    }
}
