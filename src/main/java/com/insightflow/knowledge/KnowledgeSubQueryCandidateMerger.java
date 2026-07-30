package com.insightflow.knowledge;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 将多路子查询检索候选按 RRF 合并为单一 TopN 列表。
 *
 * <p>与 {@link KnowledgeSearchResultMerger} 互补：后者合并「收窄类型 + 全类型」两轮；
 * 本类合并「同一问题的多个子查询」候选，按 chunk 去重后供精排管道使用。</p>
 */
final class KnowledgeSubQueryCandidateMerger {

    /** 与 pgvector RRF 常数一致，保证跨子查询 rank 可比。 */
    private static final int RRF_K = 60;

    private KnowledgeSubQueryCandidateMerger() {
    }

    static KnowledgeSearchResult merge(List<KnowledgeSearchResult> subResults, int candidateLimit) {
        if (subResults.isEmpty()) {
            return new KnowledgeSearchResult(List.of(), Set.of(), Set.of(), Set.of());
        }
        if (subResults.size() == 1) {
            return subResults.get(0);
        }
        Map<UUID, KnowledgeVectorStore.SearchCandidate> byChunk = new HashMap<>();
        Map<UUID, Double> rrfScores = new HashMap<>();

        for (KnowledgeSearchResult subResult : subResults) {
            List<KnowledgeVectorStore.SearchCandidate> candidates = subResult.candidates();
            for (int index = 0; index < candidates.size(); index++) {
                KnowledgeVectorStore.SearchCandidate candidate = candidates.get(index);
                UUID chunkId = candidate.chunkId();
                byChunk.putIfAbsent(chunkId, candidate);
                rrfScores.merge(chunkId, 1.0 / (RRF_K + index + 1), Double::sum);
            }
        }

        List<KnowledgeVectorStore.SearchCandidate> merged = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(Math.max(0, candidateLimit))
                .map(entry -> withScore(byChunk.get(entry.getKey()), entry.getValue()))
                .toList();

        Set<UUID> lexicalOnly = new HashSet<>();
        Set<UUID> vectorOnly = new HashSet<>();
        Set<UUID> both = new HashSet<>();
        for (KnowledgeVectorStore.SearchCandidate candidate : merged) {
            UUID chunkId = candidate.chunkId();
            boolean lexical = subResults.stream().anyMatch(result -> isLexical(chunkId, result));
            boolean vector = subResults.stream().anyMatch(result -> isVector(chunkId, result));
            if (lexical && vector) {
                both.add(chunkId);
            } else if (lexical) {
                lexicalOnly.add(chunkId);
            } else if (vector) {
                vectorOnly.add(chunkId);
            }
        }
        return new KnowledgeSearchResult(merged, lexicalOnly, vectorOnly, both);
    }

    private static KnowledgeVectorStore.SearchCandidate withScore(
            KnowledgeVectorStore.SearchCandidate candidate, double score) {
        return new KnowledgeVectorStore.SearchCandidate(
                candidate.documentId(),
                candidate.versionId(),
                candidate.versionNo(),
                candidate.chunkId(),
                candidate.title(),
                candidate.content(),
                score,
                candidate.documentType(),
                candidate.sectionHeading(),
                candidate.effectiveWindow());
    }

    private static boolean isLexical(UUID chunkId, KnowledgeSearchResult result) {
        return result.lexicalOnlyChunkIds().contains(chunkId) || result.bothSourceChunkIds().contains(chunkId);
    }

    private static boolean isVector(UUID chunkId, KnowledgeSearchResult result) {
        return result.vectorOnlyChunkIds().contains(chunkId) || result.bothSourceChunkIds().contains(chunkId);
    }
}
