package com.insightflow.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * P3：从已打分候选池中贪心选取 TopN，兼顾相关性、同文档冗余惩罚与双主体覆盖增量。
 *
 * <p>仅 CROSS/VERSION（或解析出 ≥2 实体组）走覆盖贪心；SINGLE 等题型在 P2 加权后直接按分截断 Top8，
 * 避免 val 集上 SINGLE chunk 回吐。</p>
 */
@Component
public class KnowledgeCoverageAwareSelector {

    /** 同文档已选条数线性惩罚（作用于 RRF/精排归一化分数量级）。 */
    static final double SAME_DOCUMENT_PENALTY = 0.05;

    /** 首次选入新文档时的覆盖加成。 */
    static final double NEW_DOCUMENT_BONUS = 0.04;

    /** CROSS 题：候选命中尚未覆盖的实体组时的加成。 */
    static final double UNCOVERED_ENTITY_BONUS = 0.10;

    /** Phase 4C：enforceSoftEntityCoverage 在合并池前 N 条内搜索组代表，避免 gold 落在 greedy 后深位而无法 swap。 */
    static final int ENTITY_COVERAGE_SEARCH_DEPTH = 30;

    /** 同文档第 3 条起额外惩罚，避免单文档占满 Top8。 */
    static final double EXTRA_SAME_DOCUMENT_PENALTY = 0.06;

    private final KnowledgeTitleEntityScoreBooster titleEntityScoreBooster;

    public KnowledgeCoverageAwareSelector(KnowledgeTitleEntityScoreBooster titleEntityScoreBooster) {
        this.titleEntityScoreBooster = titleEntityScoreBooster;
    }

    public List<KnowledgeVectorStore.SearchCandidate> select(
            String question,
            List<KnowledgeVectorStore.SearchCandidate> candidates,
            int finalLimit,
            KnowledgeRetrievalOptions options) {
        if (candidates == null || candidates.isEmpty() || finalLimit <= 0) {
            return List.of();
        }
        if (candidates.size() <= finalLimit) {
            return List.copyOf(candidates);
        }

        KnowledgeTitleEntityScoreBooster.QuerySignals signals =
                titleEntityScoreBooster.buildSignals(question, options);
        if (!usesCoverageSelection(signals, options)) {
            return List.copyOf(candidates.subList(0, finalLimit));
        }

        boolean coverageMode = true;
        List<KnowledgeVectorStore.SearchCandidate> pool = new ArrayList<>(candidates);
        // Phase 4C：保留合并 boosted 池快照，供 enforceSoftEntityCoverage 在 Top30 内找组代表。
        List<KnowledgeVectorStore.SearchCandidate> fullPoolSnapshot = List.copyOf(candidates);
        List<KnowledgeVectorStore.SearchCandidate> selected = new ArrayList<>(finalLimit);
        Set<UUID> selectedDocuments = new HashSet<>();
        Set<Integer> coveredEntityGroups = new HashSet<>();

        while (selected.size() < finalLimit && !pool.isEmpty()) {
            int bestIndex = pickBestIndex(pool, selected, selectedDocuments, signals, coverageMode, coveredEntityGroups);
            KnowledgeVectorStore.SearchCandidate chosen = pool.remove(bestIndex);
            selected.add(chosen);
            selectedDocuments.add(chosen.documentId());
            markCoveredGroups(chosen, signals, coveredEntityGroups);
        }

        if (signals.entityGroups().size() >= 2) {
            selected = enforceSoftEntityCoverage(
                    selected, pool, fullPoolSnapshot, signals, finalLimit);
            selected = upgradeGroupRepresentatives(selected, fullPoolSnapshot, signals);
        }
        return List.copyOf(selected);
    }

    private int pickBestIndex(
            List<KnowledgeVectorStore.SearchCandidate> pool,
            List<KnowledgeVectorStore.SearchCandidate> selected,
            Set<UUID> selectedDocuments,
            KnowledgeTitleEntityScoreBooster.QuerySignals signals,
            boolean crossMode,
            Set<Integer> coveredEntityGroups) {
        int bestIndex = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < pool.size(); index++) {
            double score = selectionScore(
                    pool.get(index),
                    selected,
                    selectedDocuments,
                    signals,
                    crossMode,
                    coveredEntityGroups,
                    pool.size() - selected.size());
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private double selectionScore(
            KnowledgeVectorStore.SearchCandidate candidate,
            List<KnowledgeVectorStore.SearchCandidate> selected,
            Set<UUID> selectedDocuments,
            KnowledgeTitleEntityScoreBooster.QuerySignals signals,
            boolean crossMode,
            Set<Integer> coveredEntityGroups,
            int remainingSlots) {
        double score = candidate.score();
        long sameDocumentCount = selected.stream()
                .filter(item -> item.documentId().equals(candidate.documentId()))
                .count();
        score -= SAME_DOCUMENT_PENALTY * sameDocumentCount;
        if (sameDocumentCount >= 2) {
            score -= EXTRA_SAME_DOCUMENT_PENALTY * (sameDocumentCount - 1);
        }
        if (!selectedDocuments.contains(candidate.documentId())) {
            score += NEW_DOCUMENT_BONUS;
        }
        if (crossMode) {
            double entityBonus = uncoveredEntityBonus(candidate, signals, coveredEntityGroups);
            if (remainingSlots <= 2 && coveredEntityGroups.size() < signals.entityGroups().size()) {
                entityBonus *= 1.5;
            }
            score += entityBonus;
        }
        score -= contentRedundancyPenalty(candidate, selected);
        return score;
    }

    private double uncoveredEntityBonus(
            KnowledgeVectorStore.SearchCandidate candidate,
            KnowledgeTitleEntityScoreBooster.QuerySignals signals,
            Set<Integer> coveredEntityGroups) {
        double bonus = 0.0;
        List<KnowledgeTitleEntityScoreBooster.EntityGroup> groups = signals.entityGroups();
        for (int index = 0; index < groups.size(); index++) {
            if (coveredEntityGroups.contains(index)) {
                continue;
            }
            if (KnowledgeTitleEntityScoreBooster.matchesGroup(candidate, groups.get(index))) {
                bonus += UNCOVERED_ENTITY_BONUS;
            }
        }
        return bonus;
    }

    private static double contentRedundancyPenalty(
            KnowledgeVectorStore.SearchCandidate candidate,
            List<KnowledgeVectorStore.SearchCandidate> selected) {
        if (candidate.sectionHeading() != null && !candidate.sectionHeading().isBlank()) {
            boolean duplicateSection = selected.stream()
                    .anyMatch(item -> candidate.documentId().equals(item.documentId())
                            && candidate.sectionHeading().equals(item.sectionHeading()));
            if (duplicateSection) {
                return 0.03;
            }
        }
        return 0.0;
    }

    private static void markCoveredGroups(
            KnowledgeVectorStore.SearchCandidate candidate,
            KnowledgeTitleEntityScoreBooster.QuerySignals signals,
            Set<Integer> coveredEntityGroups) {
        List<KnowledgeTitleEntityScoreBooster.EntityGroup> groups = signals.entityGroups();
        for (int index = 0; index < groups.size(); index++) {
            if (KnowledgeTitleEntityScoreBooster.matchesGroup(candidate, groups.get(index))) {
                coveredEntityGroups.add(index);
            }
        }
    }

    /**
     * Phase 4C：缺失实体组时从 fullPool 前 {@link #ENTITY_COVERAGE_SEARCH_DEPTH} 条（含仍在 selected
     * 之外的 chunk）找最佳组匹配并 swap，避免 gold 在 RRF rank 8–20 时被 greedy 留在池外无法晋升。
     */
    private List<KnowledgeVectorStore.SearchCandidate> enforceSoftEntityCoverage(
            List<KnowledgeVectorStore.SearchCandidate> selected,
            List<KnowledgeVectorStore.SearchCandidate> remainingPool,
            List<KnowledgeVectorStore.SearchCandidate> fullPoolSnapshot,
            KnowledgeTitleEntityScoreBooster.QuerySignals signals,
            int finalLimit) {
        if (selected.size() < finalLimit) {
            return selected;
        }
        List<KnowledgeVectorStore.SearchCandidate> result = new ArrayList<>(selected);
        Set<UUID> selectedChunkIds = new HashSet<>();
        for (KnowledgeVectorStore.SearchCandidate candidate : result) {
            selectedChunkIds.add(candidate.chunkId());
        }
        int searchDepth = Math.min(ENTITY_COVERAGE_SEARCH_DEPTH, fullPoolSnapshot.size());
        List<KnowledgeVectorStore.SearchCandidate> searchPool = fullPoolSnapshot.subList(0, searchDepth);

        for (int groupIndex = 0; groupIndex < signals.entityGroups().size(); groupIndex++) {
            KnowledgeTitleEntityScoreBooster.EntityGroup group = signals.entityGroups().get(groupIndex);
            if (groupHasRepresentative(result, group)) {
                continue;
            }
            KnowledgeVectorStore.SearchCandidate promoted = findBestGroupMatchOutsideSelected(
                    searchPool, group, selectedChunkIds);
            if (promoted == null) {
                int promoteIndex = findBestGroupMatchIndex(remainingPool, group);
                if (promoteIndex >= 0) {
                    promoted = remainingPool.get(promoteIndex);
                }
            }
            if (promoted == null) {
                continue;
            }
            int demoteIndex = findLowestScoreIndex(result);
            if (demoteIndex < 0) {
                return result;
            }
            selectedChunkIds.remove(result.get(demoteIndex).chunkId());
            result.set(demoteIndex, promoted);
            selectedChunkIds.add(promoted.chunkId());
        }
        return result;
    }

    private static KnowledgeVectorStore.SearchCandidate findBestGroupMatchOutsideSelected(
            List<KnowledgeVectorStore.SearchCandidate> searchPool,
            KnowledgeTitleEntityScoreBooster.EntityGroup group,
            Set<UUID> selectedChunkIds) {
        KnowledgeVectorStore.SearchCandidate best = null;
        int bestQuality = Integer.MIN_VALUE;
        for (int index = 0; index < searchPool.size(); index++) {
            KnowledgeVectorStore.SearchCandidate candidate = searchPool.get(index);
            if (selectedChunkIds.contains(candidate.chunkId())) {
                continue;
            }
            int quality = KnowledgeTitleEntityScoreBooster.groupMatchQuality(candidate, group, index);
            if (quality > bestQuality) {
                bestQuality = quality;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Phase 4D：每组 entity 在 Top30 内的最高 boosted 代表若未进 Top8，则替换当前最低分槽位。
     *
     * <p>dev-149 faq-match 在 RRF rank 8 但 greedy 选满同文档 chunk 时，enforceSoftEntityCoverage
     * 可能因「已有弱 FAQ 代表」而跳过；本 pass 强制纳入该组池中最高分 chunk。</p>
     */
    private List<KnowledgeVectorStore.SearchCandidate> upgradeGroupRepresentatives(
            List<KnowledgeVectorStore.SearchCandidate> selected,
            List<KnowledgeVectorStore.SearchCandidate> fullPoolSnapshot,
            KnowledgeTitleEntityScoreBooster.QuerySignals signals) {
        if (selected.isEmpty() || signals.entityGroups().isEmpty()) {
            return selected;
        }
        List<KnowledgeVectorStore.SearchCandidate> result = new ArrayList<>(selected);
        Set<UUID> selectedIds = new HashSet<>();
        for (KnowledgeVectorStore.SearchCandidate candidate : result) {
            selectedIds.add(candidate.chunkId());
        }
        int depth = Math.min(ENTITY_COVERAGE_SEARCH_DEPTH, fullPoolSnapshot.size());

        List<KnowledgeTitleEntityScoreBooster.EntityGroup> orderedGroups = new ArrayList<>(signals.entityGroups());
        orderedGroups.sort(Comparator.comparingDouble(group -> bestGroupMatchScore(fullPoolSnapshot, depth, group)));

        for (KnowledgeTitleEntityScoreBooster.EntityGroup group : orderedGroups) {
            KnowledgeVectorStore.SearchCandidate best = findBestGroupMatchInRange(
                    fullPoolSnapshot, 0, depth, group);
            if (best == null) {
                continue;
            }
            if (selectedIds.contains(best.chunkId())) {
                continue;
            }
            int bestPoolIndex = fullPoolSnapshot.indexOf(best);
            int bestQuality = KnowledgeTitleEntityScoreBooster.groupMatchQuality(best, group, bestPoolIndex);
            int currentRepIndex = findBestGroupRepresentativeIndex(result, group, fullPoolSnapshot);
            int demoteIndex = currentRepIndex >= 0
                    ? currentRepIndex
                    : findSwappableIndex(result, signals);
            if (demoteIndex < 0) {
                continue;
            }
            if (currentRepIndex >= 0) {
                int currentQuality = KnowledgeTitleEntityScoreBooster.groupMatchQuality(
                        result.get(currentRepIndex), group, fullPoolSnapshot.indexOf(result.get(currentRepIndex)));
                if (currentQuality >= bestQuality) {
                    continue;
                }
            }
            selectedIds.remove(result.get(demoteIndex).chunkId());
            result.set(demoteIndex, best);
            selectedIds.add(best.chunkId());
        }
        return result;
    }

    private static int findBestGroupRepresentativeIndex(
            List<KnowledgeVectorStore.SearchCandidate> selected,
            KnowledgeTitleEntityScoreBooster.EntityGroup group,
            List<KnowledgeVectorStore.SearchCandidate> fullPoolSnapshot) {
        int bestIndex = -1;
        int bestQuality = Integer.MIN_VALUE;
        for (int index = 0; index < selected.size(); index++) {
            KnowledgeVectorStore.SearchCandidate candidate = selected.get(index);
            int poolIndex = fullPoolSnapshot.indexOf(candidate);
            int quality = KnowledgeTitleEntityScoreBooster.groupMatchQuality(
                    candidate, group, poolIndex >= 0 ? poolIndex : index);
            if (quality > bestQuality) {
                bestQuality = quality;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static double bestGroupMatchScore(
            List<KnowledgeVectorStore.SearchCandidate> pool,
            int depth,
            KnowledgeTitleEntityScoreBooster.EntityGroup group) {
        KnowledgeVectorStore.SearchCandidate best = findBestGroupMatchInRange(pool, 0, depth, group);
        if (best == null) {
            return Double.MAX_VALUE;
        }
        int poolIndex = pool.indexOf(best);
        return -KnowledgeTitleEntityScoreBooster.groupMatchQuality(best, group, poolIndex);
    }

    private static KnowledgeVectorStore.SearchCandidate findBestGroupMatchInRange(
            List<KnowledgeVectorStore.SearchCandidate> pool,
            int fromInclusive,
            int toExclusive,
            KnowledgeTitleEntityScoreBooster.EntityGroup group) {
        KnowledgeVectorStore.SearchCandidate best = null;
        int bestQuality = Integer.MIN_VALUE;
        int upper = Math.min(toExclusive, pool.size());
        for (int index = fromInclusive; index < upper; index++) {
            KnowledgeVectorStore.SearchCandidate candidate = pool.get(index);
            int quality = KnowledgeTitleEntityScoreBooster.groupMatchQuality(candidate, group, index);
            if (quality > bestQuality) {
                bestQuality = quality;
                best = candidate;
            }
        }
        return best;
    }

    /** 优先替换对任意 entity 组均为非唯一代表的 chunk 中 boosted 分最低者。 */
    private static int findSwappableIndex(
            List<KnowledgeVectorStore.SearchCandidate> selected,
            KnowledgeTitleEntityScoreBooster.QuerySignals signals) {
        int bestIndex = -1;
        double lowestScore = Double.MAX_VALUE;
        for (int index = 0; index < selected.size(); index++) {
            if (isCriticalRepresentative(selected, index, signals)) {
                continue;
            }
            double score = selected.get(index).score();
            if (score < lowestScore) {
                lowestScore = score;
                bestIndex = index;
            }
        }
        return bestIndex >= 0 ? bestIndex : findLowestScoreIndex(selected);
    }

    private static boolean isCriticalRepresentative(
            List<KnowledgeVectorStore.SearchCandidate> selected,
            int index,
            KnowledgeTitleEntityScoreBooster.QuerySignals signals) {
        KnowledgeVectorStore.SearchCandidate candidate = selected.get(index);
        for (KnowledgeTitleEntityScoreBooster.EntityGroup group : signals.entityGroups()) {
            if (!KnowledgeTitleEntityScoreBooster.matchesGroup(candidate, group)) {
                continue;
            }
            long matchesInSelected = selected.stream()
                    .filter(item -> KnowledgeTitleEntityScoreBooster.matchesGroup(item, group))
                    .count();
            if (matchesInSelected <= 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean groupHasRepresentative(
            List<KnowledgeVectorStore.SearchCandidate> selected,
            KnowledgeTitleEntityScoreBooster.EntityGroup group) {
        for (KnowledgeVectorStore.SearchCandidate candidate : selected) {
            if (KnowledgeTitleEntityScoreBooster.matchesGroup(candidate, group)) {
                return true;
            }
        }
        return false;
    }

    private static int findBestGroupMatchIndex(
            List<KnowledgeVectorStore.SearchCandidate> pool,
            KnowledgeTitleEntityScoreBooster.EntityGroup group) {
        int bestIndex = -1;
        int bestQuality = Integer.MIN_VALUE;
        for (int index = 0; index < pool.size(); index++) {
            KnowledgeVectorStore.SearchCandidate candidate = pool.get(index);
            int quality = KnowledgeTitleEntityScoreBooster.groupMatchQuality(candidate, group, index);
            if (quality > bestQuality) {
                bestQuality = quality;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static int findLowestScoreIndex(List<KnowledgeVectorStore.SearchCandidate> selected) {
        int lowestIndex = -1;
        double lowestScore = Double.MAX_VALUE;
        for (int index = 0; index < selected.size(); index++) {
            double score = selected.get(index).score();
            if (score < lowestScore) {
                lowestScore = score;
                lowestIndex = index;
            }
        }
        return lowestIndex;
    }

    /** 金标/生产：仅多证据题型或已拆出 ≥2 实体组时启用覆盖贪心。 */
    static boolean usesCoverageSelection(
            KnowledgeTitleEntityScoreBooster.QuerySignals signals, KnowledgeRetrievalOptions options) {
        if (signals.entityGroups().size() >= 2) {
            return true;
        }
        if (options == null || options.questionTypeName() == null) {
            return false;
        }
        String type = options.questionTypeName();
        return "CROSS_DOCUMENT".equals(type)
                || "cross_document".equals(type)
                || "VERSION_CONFLICT".equals(type)
                || "version_conflict".equals(type);
    }
}
