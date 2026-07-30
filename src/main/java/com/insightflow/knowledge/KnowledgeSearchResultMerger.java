package com.insightflow.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 合并两轮检索候选：取 chunk 最高 RRF 分，保留来源统计供评测漏斗使用。
 *
 * <p>Planner 收窄类型后必须再跑全类型补检索；合并而非替换，避免词法假阳性阻断 broad 召回。</p>
 */
final class KnowledgeSearchResultMerger {

    private KnowledgeSearchResultMerger() {
    }

    static KnowledgeSearchResult merge(KnowledgeSearchResult narrowed, KnowledgeSearchResult broad, int candidateLimit) {
        Map<UUID, KnowledgeVectorStore.SearchCandidate> byChunk = new HashMap<>();
        Map<UUID, Double> bestScore = new HashMap<>();

        appendCandidates(narrowed.candidates(), byChunk, bestScore);
        appendCandidates(broad.candidates(), byChunk, bestScore);

        List<KnowledgeVectorStore.SearchCandidate> merged = byChunk.values().stream()
                .map(candidate -> withScore(candidate, bestScore.get(candidate.chunkId())))
                .sorted(Comparator.comparingDouble(KnowledgeVectorStore.SearchCandidate::score).reversed())
                .limit(Math.max(0, candidateLimit))
                .toList();

        Set<UUID> lexicalOnly = new HashSet<>();
        Set<UUID> vectorOnly = new HashSet<>();
        Set<UUID> both = new HashSet<>();
        for (KnowledgeVectorStore.SearchCandidate candidate : merged) {
            UUID chunkId = candidate.chunkId();
            boolean lexical = isLexical(chunkId, narrowed) || isLexical(chunkId, broad);
            boolean vector = isVector(chunkId, narrowed) || isVector(chunkId, broad);
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

    private static void appendCandidates(
            List<KnowledgeVectorStore.SearchCandidate> candidates,
            Map<UUID, KnowledgeVectorStore.SearchCandidate> byChunk,
            Map<UUID, Double> bestScore) {
        for (KnowledgeVectorStore.SearchCandidate candidate : candidates) {
            byChunk.putIfAbsent(candidate.chunkId(), candidate);
            bestScore.merge(candidate.chunkId(), candidate.score(), Math::max);
        }
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
