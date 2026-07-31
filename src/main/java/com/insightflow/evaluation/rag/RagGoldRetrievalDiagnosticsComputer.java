package com.insightflow.evaluation.rag;

import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.knowledge.KnowledgeEvidenceGateDecision;
import com.insightflow.knowledge.KnowledgeRerankOutcome;
import com.insightflow.knowledge.KnowledgeRetrievalDiagnostics;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeVectorStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 从 {@link KnowledgeRetrievalDiagnostics} 与金标 evidence 计算单题检索诊断。
 *
 * <p>候选列表取 RRF Top50；最终列表取精排后 Top8。rank 均基于 chunk 精确匹配。</p>
 */
public final class RagGoldRetrievalDiagnosticsComputer {

    private static final String RRF_ONLY_VERSION = "knowledge:rrf:v3";

    private RagGoldRetrievalDiagnosticsComputer() {
    }

    public static RagGoldRetrievalCaseDiagnostics compute(
            KnowledgeRetrievalDiagnostics diagnostics,
            List<RagGoldEvidenceSnapshot> evidences,
            RagGoldQuestionType questionType,
            Map<UUID, Integer> versionNumbers,
            RagGoldEvidenceMatcher matcher) {
        if (diagnostics == null) {
            return emptyDiagnostics();
        }
        List<String> candidateIds = toRuntimeIds(diagnostics.candidates());
        List<String> finalIds = diagnostics.result().evidence().stream()
                .map(item -> item.id())
                .toList();

        int goldChunkRrfRank = matcher.firstRelevantRank(evidences, candidateIds, versionNumbers);
        boolean candidateHitAt10 = matcher.hitWithinTopK(
                evidences, candidateIds, 10, versionNumbers, com.insightflow.entity.RagGoldEvidenceGranularity.CHUNK);
        boolean candidateHitAt30 = matcher.hitWithinTopK(
                evidences, candidateIds, 30, versionNumbers, com.insightflow.entity.RagGoldEvidenceGranularity.CHUNK);
        boolean candidateHitAt50 = matcher.hitWithinTopK(
                evidences, candidateIds, 50, versionNumbers, com.insightflow.entity.RagGoldEvidenceGranularity.CHUNK);

        int rerankBeforeRank = goldChunkRrfRank;
        int rerankAfterRank = matcher.firstRelevantRank(evidences, finalIds, versionNumbers);

        KnowledgeRerankOutcome rerankOutcome = diagnostics.rerankOutcome();
        String rerankerName = rerankOutcome == null
                ? RRF_ONLY_VERSION
                : rerankOutcome.rerankerName();
        Long rerankLatencyMs = rerankOutcome == null ? null : rerankOutcome.latencyMs();
        boolean rerankFallbackUsed = rerankOutcome != null && rerankOutcome.fallbackUsed();
        int rerankInputCount = rerankOutcome == null ? candidateIds.size() : rerankOutcome.inputCandidateCount();

        List<String> finalTop8DocumentIds = extractDocumentPublicIds(finalIds);
        List<String> finalTop8ChunkIds = extractChunkPublicIds(finalIds);

        boolean requirementGroupCoverageAt8 = matcher.allRequirementGroupsSatisfiedAtTopK(
                evidences, finalIds, 8, versionNumbers);
        boolean finalCrossDocumentDualHit = questionType != RagGoldQuestionType.CROSS_DOCUMENT
                || matcher.distinctDocumentsHitWithinTopK(evidences, finalIds, 8) >= 2;

        List<RagGoldRequirementGroupDiagnostics> requirementGroups = buildRequirementGroupDiagnostics(
                evidences, candidateIds, finalIds, versionNumbers, matcher);

        List<String> subQueries = diagnostics.subQueries() == null || diagnostics.subQueries().isEmpty()
                ? List.of()
                : diagnostics.subQueries();
        List<Integer> candidatesPerSubQuery = diagnostics.candidatesPerSubQuery() == null
                ? List.of()
                : diagnostics.candidatesPerSubQuery();

        KnowledgeRetrievalResult retrievalResult = diagnostics.result();
        String gateOutcome = retrievalResult == null
                ? KnowledgeEvidenceGateDecision.OUTCOME_INJECT
                : retrievalResult.gateOutcome();
        double gateTopScore = retrievalResult == null ? 0.0d : retrievalResult.gateTopScore();
        int gateInputCount = retrievalResult == null ? 0 : retrievalResult.gateInputCount();
        int gateInjectedCount = retrievalResult == null ? 0 : retrievalResult.evidence().size();

        return new RagGoldRetrievalCaseDiagnostics(
                goldChunkRrfRank,
                candidateHitAt10,
                candidateHitAt30,
                candidateHitAt50,
                rerankBeforeRank,
                rerankAfterRank,
                rerankerName,
                rerankLatencyMs,
                rerankFallbackUsed,
                rerankInputCount,
                finalTop8DocumentIds,
                finalTop8ChunkIds,
                requirementGroupCoverageAt8,
                finalCrossDocumentDualHit,
                requirementGroups,
                subQueries,
                candidatesPerSubQuery,
                gateOutcome,
                gateTopScore,
                gateInputCount,
                gateInjectedCount);
    }

    private static List<RagGoldRequirementGroupDiagnostics> buildRequirementGroupDiagnostics(
            List<RagGoldEvidenceSnapshot> evidences,
            List<String> candidateIds,
            List<String> finalIds,
            Map<UUID, Integer> versionNumbers,
            RagGoldEvidenceMatcher matcher) {
        if (evidences.isEmpty()) {
            return List.of();
        }
        Map<String, List<RagGoldEvidenceSnapshot>> groups = matcher.groupByRequirementKey(evidences);
        List<RagGoldRequirementGroupDiagnostics> result = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<RagGoldEvidenceSnapshot>> entry : groups.entrySet()) {
            int rrfRank = matcher.firstRelevantRankForGroup(entry.getValue(), candidateIds, versionNumbers);
            int finalRank = matcher.firstRelevantRankForGroup(entry.getValue(), finalIds, versionNumbers);
            boolean satisfiedAt8 = finalRank > 0 && finalRank <= 8;
            result.add(new RagGoldRequirementGroupDiagnostics(entry.getKey(), rrfRank, finalRank, satisfiedAt8));
        }
        return List.copyOf(result);
    }

    private static RagGoldRetrievalCaseDiagnostics emptyDiagnostics() {
        return new RagGoldRetrievalCaseDiagnostics(
                0, false, false, false, 0, 0,
                RRF_ONLY_VERSION, null, false, 0,
                List.of(), List.of(), false, false,
                List.of(), List.of(), List.of(),
                KnowledgeEvidenceGateDecision.OUTCOME_INJECT,
                0.0d,
                0,
                0);
    }

    private static List<String> toRuntimeIds(List<KnowledgeVectorStore.SearchCandidate> candidates) {
        List<String> ids = new ArrayList<>(candidates.size());
        for (KnowledgeVectorStore.SearchCandidate candidate : candidates) {
            ids.add("knowledge:" + candidate.documentId() + ":v" + candidate.versionNo() + ":"
                    + candidate.chunkId());
        }
        return ids;
    }

    /** 从 runtime ID 提取 document 公开 UUID（knowledge:{docUuid}:vN:chunkUuid）。 */
    static List<String> extractDocumentPublicIds(List<String> runtimeIds) {
        Set<String> docIds = new LinkedHashSet<>();
        for (String id : runtimeIds) {
            parseDocumentId(id).ifPresent(docIds::add);
        }
        return List.copyOf(docIds);
    }

    /** 从 runtime ID 提取 chunk 公开 UUID。 */
    static List<String> extractChunkPublicIds(List<String> runtimeIds) {
        Set<String> chunkIds = new LinkedHashSet<>();
        for (String id : runtimeIds) {
            parseChunkId(id).ifPresent(chunkIds::add);
        }
        return List.copyOf(chunkIds);
    }

    private static java.util.Optional<String> parseDocumentId(String runtimeId) {
        if (runtimeId == null || !runtimeId.startsWith("knowledge:")) {
            return java.util.Optional.empty();
        }
        String remainder = runtimeId.substring("knowledge:".length());
        int versionIndex = remainder.indexOf(":v");
        if (versionIndex <= 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(remainder.substring(0, versionIndex));
    }

    private static java.util.Optional<String> parseChunkId(String runtimeId) {
        if (runtimeId == null) {
            return java.util.Optional.empty();
        }
        int lastColon = runtimeId.lastIndexOf(':');
        if (lastColon <= 0 || lastColon >= runtimeId.length() - 1) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(runtimeId.substring(lastColon + 1));
    }
}
