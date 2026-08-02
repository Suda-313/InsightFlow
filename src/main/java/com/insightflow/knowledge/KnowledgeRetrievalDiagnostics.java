package com.insightflow.knowledge;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 检索诊断：最终 Top8 与 RRF 候选 Top50 及来源统计。
 *
 * <p>候选列表只保留 {@link KnowledgeVectorStore.SearchCandidate} 元数据，供评测计算
 * Candidate Recall，不写入持久化评测 JSON 的正文。</p>
 */
public record KnowledgeRetrievalDiagnostics(
        KnowledgeRetrievalResult result,
        List<KnowledgeVectorStore.SearchCandidate> candidates,
        Set<UUID> lexicalOnlyChunkIds,
        Set<UUID> vectorOnlyChunkIds,
        Set<UUID> bothSourceChunkIds,
        KnowledgeRerankOutcome rerankOutcome,
        /** CROSS 分解子查询；未分解时为单元素原问题。 */
        List<String> subQueries,
        /** 各子查询候选数，与 subQueries 对齐。 */
        List<Integer> candidatesPerSubQuery) {

    public KnowledgeRetrievalDiagnostics(
            KnowledgeRetrievalResult result,
            List<KnowledgeVectorStore.SearchCandidate> candidates,
            Set<UUID> lexicalOnlyChunkIds,
            Set<UUID> vectorOnlyChunkIds,
            Set<UUID> bothSourceChunkIds) {
        this(result, candidates, lexicalOnlyChunkIds, vectorOnlyChunkIds, bothSourceChunkIds, null, List.of(), List.of());
    }

    public KnowledgeRetrievalDiagnostics(
            KnowledgeRetrievalResult result,
            List<KnowledgeVectorStore.SearchCandidate> candidates,
            Set<UUID> lexicalOnlyChunkIds,
            Set<UUID> vectorOnlyChunkIds,
            Set<UUID> bothSourceChunkIds,
            KnowledgeRerankOutcome rerankOutcome) {
        this(result, candidates, lexicalOnlyChunkIds, vectorOnlyChunkIds, bothSourceChunkIds, rerankOutcome, List.of(), List.of());
    }

    public KnowledgeRetrievalDiagnostics {
        candidates = List.copyOf(candidates);
        lexicalOnlyChunkIds = Set.copyOf(lexicalOnlyChunkIds);
        vectorOnlyChunkIds = Set.copyOf(vectorOnlyChunkIds);
        bothSourceChunkIds = Set.copyOf(bothSourceChunkIds);
        subQueries = List.copyOf(subQueries);
        candidatesPerSubQuery = List.copyOf(candidatesPerSubQuery);
    }
}
