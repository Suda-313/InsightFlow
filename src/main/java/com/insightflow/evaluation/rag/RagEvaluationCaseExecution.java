package com.insightflow.evaluation.rag;

import com.insightflow.knowledge.KnowledgeRetrievalResult;

/**
 * 单题受控执行的临时结果。
 *
 * <p>该对象只在线程内传递给评分器和日志，不会写入 API 或评测历史；
 * 因此可以保留本题检索证据用于既有规则评分，同时避免持久化模型答案和知识正文。</p>
 */
public record RagEvaluationCaseExecution(
        /** 本题实际检索到的受控证据，仅用于本次规则评分。*/
        KnowledgeRetrievalResult retrieval,
        /** 本题最终回答，仅在运行期提取引用，随后立即丢弃。*/
        String answer,
        /** succeeded 或 failed，失败题不会中断同批后续题目。*/
        String status,
        /** retrieval_timeout、generation_timeout 或固定失败阶段，不记录供应商原始异常。*/
        String failureStage,
        /** 受控检索阶段耗时，超时时为截至收敛时的已知耗时。*/
        long retrievalLatencyMs,
        /** 最终回答阶段耗时，未进入该阶段时为零。*/
        long generationLatencyMs,
        /** 从开始到完成或收敛的总耗时，用于逐题日志。*/
        long totalLatencyMs) {

    /** 失败题使用空检索和空回答，让评分器稳定按未命中处理，不让异常内容进入结果。*/
    static RagEvaluationCaseExecution failed(String failureStage, long retrievalLatencyMs, long generationLatencyMs, long totalLatencyMs) {
        return new RagEvaluationCaseExecution(
                new KnowledgeRetrievalResult(0, java.util.List.of()), "", "failed", failureStage,
                retrievalLatencyMs, generationLatencyMs, totalLatencyMs);
    }
}
