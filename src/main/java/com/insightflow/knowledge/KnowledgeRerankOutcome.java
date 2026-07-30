package com.insightflow.knowledge;

import java.util.List;

/**
 * 精排结果与可审计元数据。
 *
 * <p>不记录模型原始推理或候选正文；只保留 reranker 标识、耗时与是否回退 RRF。</p>
 */
public record KnowledgeRerankOutcome(
        List<KnowledgeVectorStore.SearchCandidate> rankedCandidates,
        String rerankerName,
        String rerankerVersion,
        long latencyMs,
        boolean fallbackUsed,
        int inputCandidateCount) {

    public KnowledgeRerankOutcome {
        rankedCandidates = List.copyOf(rankedCandidates);
    }

    /** P3 覆盖选择后更新最终 TopN，保留精排元数据供评测诊断。 */
    public KnowledgeRerankOutcome withRankedCandidates(
            List<KnowledgeVectorStore.SearchCandidate> updatedCandidates) {
        return new KnowledgeRerankOutcome(
                updatedCandidates,
                rerankerName,
                rerankerVersion,
                latencyMs,
                fallbackUsed,
                inputCandidateCount);
    }
}
