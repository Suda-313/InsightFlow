package com.insightflow.evaluation.rag.gold.importing;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * 与 {@code evaluation/rag/gold/seeds/schema.json} 对齐的 seed 文件 DTO。
 *
 * <p>evidence 在 seed 阶段只存 {@code document_ref} 与 chunk 序号，导入时再经 manifest 解析为公开 UUID。</p>
 */
public record RagGoldSeedFile(
        @JsonProperty("dataset_key") String datasetKey,
        @JsonProperty("dataset_version") String datasetVersion,
        String split,
        @JsonProperty("source_corpus_version") String sourceCorpusVersion,
        @JsonProperty("workspace_public_id") UUID workspacePublicId,
        List<CaseSeed> cases) {

    public record CaseSeed(
            @JsonProperty("case_key") String caseKey,
            @JsonProperty("question_text") String questionText,
            @JsonProperty("question_type") String questionType,
            String difficulty,
            @JsonProperty("should_refuse") boolean shouldRefuse,
            @JsonProperty("annotation_basis") String annotationBasis,
            String reviewer,
            @JsonProperty("sort_order") int sortOrder,
            List<EvidenceSeed> evidences,
            List<AssertionSeed> assertions) {
    }

    public record EvidenceSeed(
            String granularity,
            @JsonProperty("document_ref") String documentRef,
            @JsonProperty("version_no") int versionNo,
            @JsonProperty("chunk_no") int chunkNo,
            @JsonProperty("requirement_key") String requirementKey) {

        public EvidenceSeed(String granularity, String documentRef, int versionNo, int chunkNo) {
            this(granularity, documentRef, versionNo, chunkNo, null);
        }
    }

    public record AssertionSeed(
            @JsonProperty("assertion_type") String assertionType,
            @JsonProperty("assertion_text") String assertionText,
            double weight) {
    }
}
