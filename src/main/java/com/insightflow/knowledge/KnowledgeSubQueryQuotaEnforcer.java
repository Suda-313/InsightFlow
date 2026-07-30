package com.insightflow.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Phase 3/4B：多路子查询 CROSS 题在 Top8 中为每路检索保留最低配额，再交 P3 覆盖贪心填满剩余位。
 *
 * <p>Phase 3 发现：dev-154 等题中，合并 RRF 后单文档 chunk 占满候选池，覆盖贪心会把某路子查询
 * （如 signin-window）的高分 chunk 挤出 Top8；先从各路子查询本地 Top-N 各锁 1 条。</p>
 *
 * <p>Phase 4B 修正：配额代表必须来自各子查询**本地排名 Top1**（{@code subResult.candidates().get(0)}），
 * 而非全局合并池中「首个属于该子查询 eligible 集合的 chunk」。旧逻辑在某路 gold chunk 仅在本路
 * 高分、全局 RRF 排名靠后（如 gushu-window 全局第 10 但前 9 条均来自其他子查询）时，会选到同一
 * eligible 集合中某个全局排名更靠前的非 gold chunk 充当配额，导致 gold 未被保留。
 * 直接取本地 Top1 消除了全局排序对配额代表选取的干扰。</p>
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

        List<KnowledgeVectorStore.SearchCandidate> reserved = new ArrayList<>();
        Set<UUID> reservedChunkIds = new HashSet<>();

        // Phase 4B：为每路子查询从其本地 Top1 取配额代表，而非扫描全局 rankedPool。
        // 全局池在 gold 只出现于本路、RRF 排名靠后时会选到更靠前的非 gold eligible chunk；
        // 直接取 candidates.get(0)（子查询本地最高分）排除全局顺序的干扰。
        for (int index = 0; index < subResults.size(); index++) {
            // 守卫：剩余未处理子查询占用的潜在槽位加上已预留数不得超限，
            // 避免前几路把所有槽位填满导致后面子查询无法获得配额。
            if (reserved.size() + (subResults.size() - index - 1) >= finalLimit) {
                break;
            }
            if (reserved.size() >= finalLimit) {
                break;
            }
            KnowledgeVectorStore.SearchCandidate pick = pickLocalTop(subResults.get(index), reservedChunkIds);
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
     * Phase 4B：直接取该子查询本地排名 Top1 作为配额代表。
     *
     * <p>只看 {@code candidates.get(0)}（子查询本地最高分 chunk），而非扫描全局合并池寻找首个
     * eligible 条目。若 Top1 已被前一路子查询保留（KISS），则跳过本路配额，不尝试 Top2；
     * 剩余槽位统一由后续覆盖贪心填充。</p>
     *
     * <p>注意：此处不再使用 {@code SUB_QUERY_LOOKBACK} 窗口限制，因为只取第 0 条不存在拉入
     * 尾部噪声的风险；窗口参数保留供未来多槽配额场景复用。</p>
     */
    private static KnowledgeVectorStore.SearchCandidate pickLocalTop(
            KnowledgeSearchResult subResult,
            Set<UUID> excludeChunkIds) {
        if (subResult.candidates().isEmpty()) {
            return null;
        }
        // 取本地排名第 1 条；若已被其他子查询保留则放弃，不降级到 Top2（KISS）。
        KnowledgeVectorStore.SearchCandidate top1 = subResult.candidates().get(0);
        return excludeChunkIds.contains(top1.chunkId()) ? null : top1;
    }

    private static List<KnowledgeVectorStore.SearchCandidate> sortByScore(
            List<KnowledgeVectorStore.SearchCandidate> candidates) {
        List<KnowledgeVectorStore.SearchCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(KnowledgeVectorStore.SearchCandidate::score).reversed());
        return List.copyOf(sorted);
    }
}
