package com.insightflow.knowledge;

import com.insightflow.entity.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Phase 2：对含事件编号的问题，在 RRF 合并后补检索未进池的标识符命中 chunk。
 *
 * <p>仅做确定性 ILIKE 补召回，不绕过组织/Workspace/发布/effective window 过滤；
 * 与 {@link KnowledgeSearchResultMerger} 合并，保证 dev-147 类 gold 进入 Candidate@50。</p>
 */
@Component
public class KnowledgeIdentifierCandidateSupplement {

    /** 每个标识符最多补入条数，避免噪声占满 Top50。 */
    static final int PER_IDENTIFIER_LIMIT = 5;

    /** 补召回 chunk 的融合分：与 RRF Top 档可比，便于后续 P2 加权再排序。 */
    static final double SUPPLEMENT_SCORE = 0.12;

    private final KnowledgeVectorStore vectors;

    public KnowledgeIdentifierCandidateSupplement(KnowledgeVectorStore vectors) {
        this.vectors = vectors;
    }

    /**
     * 对每个标识符执行 ILIKE 补检索并并入候选（去重合并，不因池内已有同编号其他 chunk 而跳过）。
     */
    KnowledgeSearchResult supplement(Workspace workspace, String question, KnowledgeSearchResult merged) {
        Set<String> identifiers = KnowledgeIdentifierExtractor.extractEventIds(question);
        if (identifiers.isEmpty()) {
            return merged;
        }
        int candidateLimit = KnowledgeSearchOptions.rrfV2("").candidateLimit();
        KnowledgeSearchResult combined = merged;
        for (String identifier : identifiers) {
            KnowledgeSearchResult idHits = vectors.searchByExactIdentifier(
                    workspace.getOrganizationId(),
                    workspace.getId(),
                    identifier,
                    PER_IDENTIFIER_LIMIT,
                    SUPPLEMENT_SCORE);
            if (idHits.candidates().isEmpty()) {
                continue;
            }
            combined = KnowledgeSearchResultMerger.merge(combined, idHits, candidateLimit);
        }
        return combined;
    }

    static boolean candidateMatchesIdentifier(
            KnowledgeVectorStore.SearchCandidate candidate, String identifier) {
        return KnowledgeIdentifierExtractor.containsExact(candidate.title(), identifier)
                || KnowledgeIdentifierExtractor.containsExact(candidate.sectionHeading(), identifier)
                || KnowledgeIdentifierExtractor.containsExact(candidate.content(), identifier);
    }
}
