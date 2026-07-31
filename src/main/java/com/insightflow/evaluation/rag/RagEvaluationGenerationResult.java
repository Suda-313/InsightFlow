package com.insightflow.evaluation.rag;

/**
 * RAG 评测单次模型调用的受控结果。
 *
 * <p>只保留最终回答与 Usage 计数，不保存 system prompt、思维链或供应商原始响应体。</p>
 */
public record RagEvaluationGenerationResult(
        String answer,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens) {

    /** 失败或未进入生成阶段时的空结果。 */
    public static RagEvaluationGenerationResult empty() {
        return new RagEvaluationGenerationResult("", null, null, null);
    }
}
