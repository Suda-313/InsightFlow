package com.insightflow.knowledge;

import java.util.ArrayList;
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
            selected = enforceSoftEntityCoverage(selected, pool, signals, finalLimit);
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

    private List<KnowledgeVectorStore.SearchCandidate> enforceSoftEntityCoverage(
            List<KnowledgeVectorStore.SearchCandidate> selected,
            List<KnowledgeVectorStore.SearchCandidate> remainingPool,
            KnowledgeTitleEntityScoreBooster.QuerySignals signals,
            int finalLimit) {
        if (selected.size() < finalLimit || remainingPool.isEmpty()) {
            return selected;
        }
        List<KnowledgeVectorStore.SearchCandidate> result = new ArrayList<>(selected);
        for (int groupIndex = 0; groupIndex < signals.entityGroups().size(); groupIndex++) {
            if (groupHasRepresentative(result, signals.entityGroups().get(groupIndex))) {
                continue;
            }
            int promoteIndex = findBestGroupMatchIndex(remainingPool, signals.entityGroups().get(groupIndex));
            if (promoteIndex < 0) {
                continue;
            }
            KnowledgeVectorStore.SearchCandidate promoted = remainingPool.remove(promoteIndex);
            int demoteIndex = findLowestScoreIndex(result);
            if (demoteIndex < 0) {
                return result;
            }
            result.set(demoteIndex, promoted);
        }
        return result;
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
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < pool.size(); index++) {
            KnowledgeVectorStore.SearchCandidate candidate = pool.get(index);
            if (!KnowledgeTitleEntityScoreBooster.matchesGroup(candidate, group)) {
                continue;
            }
            if (candidate.score() > bestScore) {
                bestScore = candidate.score();
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
