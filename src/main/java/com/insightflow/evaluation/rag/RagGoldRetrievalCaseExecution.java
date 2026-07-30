package com.insightflow.evaluation.rag;

import com.insightflow.knowledge.KnowledgeRetrievalDiagnostics;

/**
 * retrieval-only 单题执行结果。
 */
public record RagGoldRetrievalCaseExecution(
        KnowledgeRetrievalDiagnostics diagnostics,
        String status,
        String failureStage,
        long retrievalLatencyMs,
        long totalLatencyMs) {

    static RagGoldRetrievalCaseExecution succeeded(
            KnowledgeRetrievalDiagnostics diagnostics, long retrievalLatencyMs, long totalLatencyMs) {
        return new RagGoldRetrievalCaseExecution(diagnostics, "succeeded", null, retrievalLatencyMs, totalLatencyMs);
    }

    static RagGoldRetrievalCaseExecution failed(String failureStage, long totalLatencyMs) {
        return new RagGoldRetrievalCaseExecution(null, "failed", failureStage, totalLatencyMs, totalLatencyMs);
    }
}
