package com.insightflow.knowledge;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 混合检索的诊断结果：候选排序与来源统计，供评测漏斗使用。
 *
 * <p>不携带问题原文或 chunk 正文；来源集合只含 chunk public_id。</p>
 */
public record KnowledgeSearchResult(
        List<KnowledgeVectorStore.SearchCandidate> candidates,
        Set<UUID> lexicalOnlyChunkIds,
        Set<UUID> vectorOnlyChunkIds,
        Set<UUID> bothSourceChunkIds) {

    public KnowledgeSearchResult {
        candidates = List.copyOf(candidates);
        lexicalOnlyChunkIds = Set.copyOf(lexicalOnlyChunkIds);
        vectorOnlyChunkIds = Set.copyOf(vectorOnlyChunkIds);
        bothSourceChunkIds = Set.copyOf(bothSourceChunkIds);
    }
}
