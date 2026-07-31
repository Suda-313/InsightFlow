package com.insightflow.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Phase 3/4C：多路子查询 CROSS 题在 Top8 中为每路检索保留最低配额，再交 P3 覆盖贪心填满剩余位。
 *
 * <p>Phase 4C 相对 Phase 3 的改进：</p>
 * <ul>
 *   <li>配额处理顺序按「该路 eligible 在合并池中的最高 boosted 分」升序（最弱子查询优先），
 *       避免强势子查询先占共享 chunk 导致弱势路只能落到低分代表；</li>
 *   <li>每路配额在 lookback 窗口内取<strong> boosted 分最高</strong>的 eligible chunk
 *       （显式 max 扫描，并与子查询索引对齐的 entityGroup 做 tie-break），
 *       而非 Phase 4B 的盲取本地 index=0。</li>
 * </ul>
 */
@Component
public class KnowledgeSubQueryQuotaEnforcer {

    /** 每路子查询在最终 Top8 中至少保留 1 条代表。 */
    static final int MIN_SLOTS_PER_SUB_QUERY = 1;

    /**
     * 子查询本地排名窗口：只从各路 Top-N 内挑配额代表，避免把子查询尾部噪声拉进 Top8。
     * dev-154 signin-window gold 在合并 RRF 约第 16 位，窗口需 ≥16。
     */
    static final int SUB_QUERY_LOOKBACK = 20;

    /** 子查询索引与 entityGroup 对齐时的 tie-break 增量（不改变全局排序主序）。 */
    static final double ENTITY_GROUP_QUOTA_TIE_BREAK = 0.001;

    private final KnowledgeTitleEntityScoreBooster titleEntityScoreBooster;

    public KnowledgeSubQueryQuotaEnforcer(KnowledgeTitleEntityScoreBooster titleEntityScoreBooster) {
        this.titleEntityScoreBooster = titleEntityScoreBooster;
    }

    /**
     * 在已精排/加权的合并池中选取 TopN 证据；多路子查询且走覆盖选择时启用配额预留。
     */
    public List<KnowledgeVectorStore.SearchCandidate> selectTopEvidence(
            String question,
            List<KnowledgeVectorStore.SearchCandidate> rankedPool,
            int finalLimit,
            KnowledgeRetrievalOptions options,
            List<KnowledgeSearchResult> subResults,
            KnowledgeCoverageAwareSelector coverageSelector) {
        if (rankedPool == null || rankedPool.isEmpty() || finalLimit <= 0) {
            return List.of();
        }
        if (subResults == null || subResults.size() < 2) {
            return coverageSelector.select(question, rankedPool, finalLimit, options);
        }

        KnowledgeTitleEntityScoreBooster.QuerySignals signals =
                titleEntityScoreBooster.buildSignals(question, options);
        if (!KnowledgeCoverageAwareSelector.usesCoverageSelection(signals, options)) {
            return coverageSelector.select(question, rankedPool, finalLimit, options);
        }

        List<Set<UUID>> eligibleBySubQuery = buildEligibleChunkSets(subResults);
        List<Integer> subQueryOrder = orderSubQueriesWeakestFirst(rankedPool, eligibleBySubQuery);
        List<KnowledgeVectorStore.SearchCandidate> reserved = new ArrayList<>();
        Set<UUID> reservedChunkIds = new HashSet<>();

        for (int processed = 0; processed < subQueryOrder.size(); processed++) {
            int subQueryIndex = subQueryOrder.get(processed);
            if (reserved.size() + (subQueryOrder.size() - processed - 1) >= finalLimit) {
                break;
            }
            if (reserved.size() >= finalLimit) {
                break;
            }
            KnowledgeTitleEntityScoreBooster.EntityGroup alignedGroup = alignedEntityGroup(signals, subQueryIndex);
            KnowledgeVectorStore.SearchCandidate pick = pickHighestScoreEligible(
                    rankedPool,
                    eligibleBySubQuery.get(subQueryIndex),
                    reservedChunkIds,
                    alignedGroup);
            if (pick != null) {
                reserved.add(pick);
                reservedChunkIds.add(pick.chunkId());
            }
        }

        if (reserved.size() >= finalLimit) {
            return sortByScore(reserved.subList(0, finalLimit));
        }

        List<KnowledgeVectorStore.SearchCandidate> remainingPool = rankedPool.stream()
                .filter(candidate -> !reservedChunkIds.contains(candidate.chunkId()))
                .toList();
        int fillCount = finalLimit - reserved.size();
        List<KnowledgeVectorStore.SearchCandidate> filled = coverageSelector.select(
                question, remainingPool, fillCount, options);

        List<KnowledgeVectorStore.SearchCandidate> combined = new ArrayList<>(reserved.size() + filled.size());
        combined.addAll(reserved);
        combined.addAll(filled);
        return sortByScore(combined);
    }

    /**
     * Phase 4C：按各路 eligible 在合并 boosted 池中的最高分升序排列，最弱子查询先占配额槽。
     */
    static List<Integer> orderSubQueriesWeakestFirst(
            List<KnowledgeVectorStore.SearchCandidate> rankedPool,
            List<Set<UUID>> eligibleBySubQuery) {
        List<Integer> order = new ArrayList<>(eligibleBySubQuery.size());
        for (int index = 0; index < eligibleBySubQuery.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator.comparingDouble(
                index -> maxEligibleBoostedScore(rankedPool, eligibleBySubQuery.get(index))));
        return order;
    }

    private static double maxEligibleBoostedScore(
            List<KnowledgeVectorStore.SearchCandidate> rankedPool, Set<UUID> eligibleChunkIds) {
        double max = Double.NEGATIVE_INFINITY;
        for (KnowledgeVectorStore.SearchCandidate candidate : rankedPool) {
            if (eligibleChunkIds.contains(candidate.chunkId())) {
                max = Math.max(max, candidate.score());
            }
        }
        return max == Double.NEGATIVE_INFINITY ? Double.MAX_VALUE : max;
    }

    private static KnowledgeTitleEntityScoreBooster.EntityGroup alignedEntityGroup(
            KnowledgeTitleEntityScoreBooster.QuerySignals signals, int subQueryIndex) {
        if (signals.entityGroups().size() > subQueryIndex) {
            return signals.entityGroups().get(subQueryIndex);
        }
        return null;
    }

    /**
     * 在合并 boosted 池中，从 eligible 集合里取得分最高的一条；同分优先匹配本子查询 entityGroup。
     */
    static KnowledgeVectorStore.SearchCandidate pickHighestScoreEligible(
            List<KnowledgeVectorStore.SearchCandidate> rankedPool,
            Set<UUID> eligibleChunkIds,
            Set<UUID> excludeChunkIds,
            KnowledgeTitleEntityScoreBooster.EntityGroup alignedGroup) {
        KnowledgeVectorStore.SearchCandidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (KnowledgeVectorStore.SearchCandidate candidate : rankedPool) {
            UUID chunkId = candidate.chunkId();
            if (!eligibleChunkIds.contains(chunkId) || excludeChunkIds.contains(chunkId)) {
                continue;
            }
            double score = candidate.score();
            if (alignedGroup != null
                    && KnowledgeTitleEntityScoreBooster.matchesGroup(candidate, alignedGroup)) {
                score += ENTITY_GROUP_QUOTA_TIE_BREAK;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static List<Set<UUID>> buildEligibleChunkSets(List<KnowledgeSearchResult> subResults) {
        List<Set<UUID>> sets = new ArrayList<>(subResults.size());
        for (KnowledgeSearchResult result : subResults) {
            Set<UUID> chunkIds = new LinkedHashSet<>();
            List<KnowledgeVectorStore.SearchCandidate> candidates = result.candidates();
            int limit = Math.min(SUB_QUERY_LOOKBACK, candidates.size());
            for (int index = 0; index < limit; index++) {
                chunkIds.add(candidates.get(index).chunkId());
            }
            sets.add(chunkIds);
        }
        return sets;
    }

    private static List<KnowledgeVectorStore.SearchCandidate> sortByScore(
            List<KnowledgeVectorStore.SearchCandidate> candidates) {
        List<KnowledgeVectorStore.SearchCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(KnowledgeVectorStore.SearchCandidate::score).reversed());
        return List.copyOf(sorted);
    }
}
