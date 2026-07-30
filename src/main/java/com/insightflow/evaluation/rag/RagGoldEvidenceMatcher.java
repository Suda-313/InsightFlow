package com.insightflow.evaluation.rag;

import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 将金标 evidence 快照解析为运行时 evidence ID 前缀或精确 ID。
 *
 * <p>运行时 ID 形如 {@code knowledge:{docUuid}:v{versionNo}:{chunkUuid}}，而金标只存公开 UUID；
 * 因此 VERSION/CHUNK 粒度需要查版本号后再构造匹配模式。</p>
 */
@Component
public class RagGoldEvidenceMatcher {

    private final KnowledgeDocumentVersionRepository versionRepository;

    public RagGoldEvidenceMatcher(KnowledgeDocumentVersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    /** 从 evidence 列表收集 versionPublicId 并批量解析为 versionNo。 */
    public Map<UUID, Integer> resolveVersionNumbersFromEvidences(List<RagGoldEvidenceSnapshot> evidences) {
        Set<UUID> versionIds = evidences.stream()
                .map(RagGoldEvidenceSnapshot::versionPublicId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        return resolveVersionNumbersByPublicIds(versionIds);
    }

    /** 按公开 UUID 批量查版本号；未找到的版本不参与 CHUNK/VERSION 精确匹配。 */
    public Map<UUID, Integer> resolveVersionNumbersByPublicIds(Set<UUID> versionPublicIds) {
        if (versionPublicIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> resolved = new HashMap<>();
        for (KnowledgeDocumentVersion version : versionRepository.findByPublicIdIn(versionPublicIds)) {
            resolved.put(version.getPublicId(), version.getVersionNo());
        }
        return resolved;
    }

    /**
     * 判断单个运行时 ID 是否满足金标 evidence 约束。
     *
     * <p>按 evidence 自身粒度匹配：CHUNK 需精确 chunk ID，DOCUMENT 仅比文档前缀。</p>
     */
    public boolean matchesEvidence(
            RagGoldEvidenceSnapshot evidence, String runtimeId, Map<UUID, Integer> versionNumbers) {
        if (runtimeId == null || evidence.documentPublicId() == null) {
            return false;
        }
        String docPrefix = "knowledge:" + evidence.documentPublicId() + ":";
        return switch (evidence.granularity()) {
            case DOCUMENT -> runtimeId.startsWith(docPrefix);
            case VERSION -> {
                Integer versionNo = versionNumbers.get(evidence.versionPublicId());
                yield versionNo != null && runtimeId.startsWith(docPrefix + "v" + versionNo + ":");
            }
            case CHUNK -> {
                Integer versionNo = versionNumbers.get(evidence.versionPublicId());
                yield versionNo != null
                        && runtimeId.equals(docPrefix + "v" + versionNo + ":" + evidence.chunkPublicId());
            }
        };
    }

    /**
     * 文档级命中：只要 runtime ID 属于同一 documentPublicId 即算 relevant。
     *
     * <p>用于 document Recall@K，与 chunk 精确匹配解耦——检索到同文档其它 chunk 仍计为文档命中。</p>
     */
    public boolean matchesDocument(RagGoldEvidenceSnapshot evidence, String runtimeId) {
        if (runtimeId == null || evidence.documentPublicId() == null) {
            return false;
        }
        return runtimeId.startsWith("knowledge:" + evidence.documentPublicId() + ":");
    }

    /** 返回 ranked 前 k 内命中的 distinct documentPublicId 数量（文档级）。 */
    public int distinctDocumentsHitWithinTopK(
            List<RagGoldEvidenceSnapshot> expected, List<String> rankedIds, int k) {
        if (expected.isEmpty()) {
            return 0;
        }
        java.util.Set<UUID> hitDocuments = new java.util.LinkedHashSet<>();
        int limit = Math.min(k, rankedIds.size());
        for (int rank = 1; rank <= limit; rank++) {
            String id = rankedIds.get(rank - 1);
            for (RagGoldEvidenceSnapshot evidence : expected) {
                if (matchesDocument(evidence, id)) {
                    hitDocuments.add(evidence.documentPublicId());
                }
            }
        }
        return hitDocuments.size();
    }

    /** 判断 ranked 前 k 个结果是否命中任一期望 evidence（用于 Recall@k）。 */
    public boolean hitWithinTopK(
            List<RagGoldEvidenceSnapshot> expected,
            List<String> rankedIds,
            int k,
            Map<UUID, Integer> versionNumbers,
            RagGoldEvidenceGranularity granularityFilter) {
        List<RagGoldEvidenceSnapshot> filtered = expected.stream()
                .filter(evidence -> matchesGranularityFilter(evidence, granularityFilter))
                .toList();
        if (filtered.isEmpty()) {
            return false;
        }
        int limit = Math.min(k, rankedIds.size());
        boolean documentLevel = granularityFilter == RagGoldEvidenceGranularity.DOCUMENT;
        for (int rank = 1; rank <= limit; rank++) {
            String id = rankedIds.get(rank - 1);
            for (RagGoldEvidenceSnapshot evidence : filtered) {
                boolean hit = documentLevel
                        ? matchesDocument(evidence, id)
                        : matchesEvidence(evidence, id, versionNumbers);
                if (hit) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 返回首个命中 relevant evidence 的 rank（1-based），未命中返回 0。 */
    public int firstRelevantRank(
            List<RagGoldEvidenceSnapshot> expected,
            List<String> rankedIds,
            Map<UUID, Integer> versionNumbers) {
        for (int rank = 1; rank <= rankedIds.size(); rank++) {
            String id = rankedIds.get(rank - 1);
            for (RagGoldEvidenceSnapshot evidence : expected) {
                if (matchesEvidence(evidence, id, versionNumbers)) {
                    return rank;
                }
            }
        }
        return 0;
    }

    /** 返回单个 requirement 组内首个 relevant chunk 的 rank（1-based）；组内 OR 语义。 */
    public int firstRelevantRankForGroup(
            List<RagGoldEvidenceSnapshot> group,
            List<String> rankedIds,
            Map<UUID, Integer> versionNumbers) {
        if (group == null || group.isEmpty()) {
            return 0;
        }
        for (int rank = 1; rank <= rankedIds.size(); rank++) {
            String id = rankedIds.get(rank - 1);
            for (RagGoldEvidenceSnapshot evidence : group) {
                if (matchesEvidence(evidence, id, versionNumbers)) {
                    return rank;
                }
            }
        }
        return 0;
    }

    /**
     * 将 evidence 按 requirement_key 分组；无 key 时每条 evidence 独立成组（AND 语义）。
     *
     * <p>同组内任一 chunk 命中即满足该组；所有组均满足才返回 true。</p>
     */
    public boolean allRequirementGroupsSatisfiedAtTopK(
            List<RagGoldEvidenceSnapshot> expected,
            List<String> rankedIds,
            int k,
            Map<UUID, Integer> versionNumbers) {
        if (expected.isEmpty()) {
            return false;
        }
        Map<String, List<RagGoldEvidenceSnapshot>> groups = groupByRequirementKey(expected);
        int limit = Math.min(k, rankedIds.size());
        for (List<RagGoldEvidenceSnapshot> group : groups.values()) {
            boolean groupSatisfied = false;
            for (int rank = 1; rank <= limit; rank++) {
                String id = rankedIds.get(rank - 1);
                for (RagGoldEvidenceSnapshot evidence : group) {
                    if (matchesEvidence(evidence, id, versionNumbers)) {
                        groupSatisfied = true;
                        break;
                    }
                }
                if (groupSatisfied) {
                    break;
                }
            }
            if (!groupSatisfied) {
                return false;
            }
        }
        return true;
    }

    /** 返回 TopK 内已满足的需求组数量与总组数。 */
    public RequirementGroupCoverage requirementGroupCoverageAtTopK(
            List<RagGoldEvidenceSnapshot> expected,
            List<String> rankedIds,
            int k,
            Map<UUID, Integer> versionNumbers) {
        Map<String, List<RagGoldEvidenceSnapshot>> groups = groupByRequirementKey(expected);
        if (groups.isEmpty()) {
            return new RequirementGroupCoverage(0, 0);
        }
        int satisfied = 0;
        for (List<RagGoldEvidenceSnapshot> group : groups.values()) {
            if (groupSatisfiedAtTopK(group, rankedIds, k, versionNumbers)) {
                satisfied++;
            }
        }
        return new RequirementGroupCoverage(satisfied, groups.size());
    }

    /** 需求组覆盖计数：分子为已满足组数，分母为总组数。 */
    public record RequirementGroupCoverage(int satisfiedGroups, int totalGroups) {
    }

    Map<String, List<RagGoldEvidenceSnapshot>> groupByRequirementKey(List<RagGoldEvidenceSnapshot> expected) {
        Map<String, List<RagGoldEvidenceSnapshot>> groups = new LinkedHashMap<>();
        for (int index = 0; index < expected.size(); index++) {
            RagGoldEvidenceSnapshot evidence = expected.get(index);
            String key = evidence.requirementKey();
            if (key == null || key.isBlank()) {
                key = "__solo_" + index;
            }
            groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(evidence);
        }
        return groups;
    }

    private boolean groupSatisfiedAtTopK(
            List<RagGoldEvidenceSnapshot> group,
            List<String> rankedIds,
            int k,
            Map<UUID, Integer> versionNumbers) {
        int limit = Math.min(k, rankedIds.size());
        for (int rank = 1; rank <= limit; rank++) {
            String id = rankedIds.get(rank - 1);
            for (RagGoldEvidenceSnapshot evidence : group) {
                if (matchesEvidence(evidence, id, versionNumbers)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 生成 legacy 前缀集合，供 {@link RagGoldEvaluationRunner} 复用。 */
    public Set<String> toLegacyPrefixes(
            List<RagGoldEvidenceSnapshot> evidences, Map<UUID, Integer> versionNumbers) {
        return evidences.stream()
                .map(evidence -> toLegacyPrefix(evidence, versionNumbers))
                .collect(Collectors.toSet());
    }

    private boolean matchesGranularityFilter(
            RagGoldEvidenceSnapshot evidence, RagGoldEvidenceGranularity granularityFilter) {
        if (granularityFilter == null) {
            return true;
        }
        if (granularityFilter == RagGoldEvidenceGranularity.DOCUMENT) {
            // 文档级 Recall：任意粒度的 evidence 都代表“应命中该文档”。
            return true;
        }
        // chunk 级指标：VERSION 与 CHUNK 粒度均计入
        return evidence.granularity() == RagGoldEvidenceGranularity.CHUNK
                || evidence.granularity() == RagGoldEvidenceGranularity.VERSION;
    }

    private String toLegacyPrefix(RagGoldEvidenceSnapshot evidence, Map<UUID, Integer> versionNumbers) {
        String docPrefix = "knowledge:" + evidence.documentPublicId() + ":";
        return switch (evidence.granularity()) {
            case DOCUMENT -> docPrefix;
            case VERSION -> {
                Integer versionNo = versionNumbers.get(evidence.versionPublicId());
                yield versionNo == null ? docPrefix : docPrefix + "v" + versionNo + ":";
            }
            case CHUNK -> {
                Integer versionNo = versionNumbers.get(evidence.versionPublicId());
                yield versionNo == null
                        ? docPrefix
                        : docPrefix + "v" + versionNo + ":" + evidence.chunkPublicId();
            }
        };
    }
}
