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
            List<AssertionSeed> assertions,
            /**
             * 本题的前序对话轮次，按时间正序；null 或空表示单轮自足问题。
             * question_text 始终是最后一轮用户提问，evidence 标注针对该轮的正确答案。
             */
            @JsonProperty("context_turns") List<ContextTurn> contextTurns) {

        /** 兼容旧 seed：未写 context_turns 时视为单轮题。 */
        public CaseSeed(
                String caseKey,
                String questionText,
                String questionType,
                String difficulty,
                boolean shouldRefuse,
                String annotationBasis,
                String reviewer,
                int sortOrder,
                List<EvidenceSeed> evidences,
                List<AssertionSeed> assertions) {
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

    /** 一条前序对话消息；role 只允许 user / assistant，与 chat_message 表语义一致。 */
    public record ContextTurn(String role, String content) {
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
