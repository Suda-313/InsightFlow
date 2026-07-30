package com.insightflow.evaluation.rag;

import java.util.List;

/**
 * 一次人工金标 RAG 评测批次的冻结元数据上下文。
 *
 * <p>Runner 持久化这些字段以便历史批次可复核，不写入题目正文或模型输出。</p>
 */
public record RagGoldManualRunContext(
        String datasetKey,
        String datasetVersionLabel,
        String split,
        String checksum,
        List<String> caseKeys,
        String promptVersion,
        String embeddingModel,
        String retrievalConfigVersion,
        /** end-to-end 或 retrieval-only。 */
        String evaluationMode) {

    public RagGoldManualRunContext {
        caseKeys = List.copyOf(caseKeys);
        if (evaluationMode == null || evaluationMode.isBlank()) {
            evaluationMode = "end-to-end";
        }
    }

    /** 兼容旧调用：默认端到端模式。 */
    public RagGoldManualRunContext(
            String datasetKey,
            String datasetVersionLabel,
            String split,
            String checksum,
            List<String> caseKeys,
            String promptVersion,
            String embeddingModel,
            String retrievalConfigVersion) {
        this(datasetKey, datasetVersionLabel, split, checksum, caseKeys,
                promptVersion, embeddingModel, retrievalConfigVersion, "end-to-end");
    }
}
