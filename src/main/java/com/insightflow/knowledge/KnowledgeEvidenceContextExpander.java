package com.insightflow.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Small-to-big 展示层：检索仍按 chunk 命中，注入 Prompt 前把同 section 切片合并为更完整上下文。
 *
 * <p>优先整段 section（同 {@code section_heading}、同版本）；超出上限时从命中 chunk 向两侧连续扩展，
 * 直到达到 {@link #EVIDENCE_CONTEXT_MAX_CHARACTERS}。不改变 Top50/Top8 候选与评测 Recall@8 口径。</p>
 */
@Component
public class KnowledgeEvidenceContextExpander {

    /** 单条证据注入 Prompt 的正文上限（展示层；检索与 Recall 仍按 chunk）。 */
    static final int EVIDENCE_CONTEXT_MAX_CHARACTERS = 1000;

    private final KnowledgeVectorStore vectors;

    public KnowledgeEvidenceContextExpander(KnowledgeVectorStore vectors) {
        this.vectors = vectors;
    }

    /**
     * 批量扩展 Top8 命中切片的展示正文，避免逐条查库。
     *
     * @return chunkId → 合并后的 snippet（已截断至上限）
     */
    public Map<UUID, String> expandBatch(
            Long organizationId, Long workspaceId, List<KnowledgeVectorStore.SearchCandidate> hits) {
        if (hits == null || hits.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<KnowledgeSectionChunkSlice>> slicesByChunk =
                vectors.loadSectionChunksBatch(organizationId, workspaceId, hits);
        if (slicesByChunk == null) {
            slicesByChunk = Map.of();
        }
        Map<UUID, String> expanded = new HashMap<>(hits.size());
        for (KnowledgeVectorStore.SearchCandidate hit : hits) {
            List<KnowledgeSectionChunkSlice> slices = slicesByChunk.get(hit.chunkId());
            expanded.put(
                    hit.chunkId(),
                    expandForHit(hit, slices == null ? List.of() : slices));
        }
        return expanded;
    }

    /**
     * 纯函数合并逻辑，便于单测；DB 无 section 成员时回退为命中 chunk 原文。
     */
    static String expandForHit(
            KnowledgeVectorStore.SearchCandidate hit, List<KnowledgeSectionChunkSlice> sectionSlices) {
        if (sectionSlices == null || sectionSlices.isEmpty()) {
            return truncate(hit.content(), EVIDENCE_CONTEXT_MAX_CHARACTERS);
        }
        List<KnowledgeSectionChunkSlice> sorted = sectionSlices.stream()
                .sorted(Comparator.comparingInt(KnowledgeSectionChunkSlice::chunkNo))
                .toList();
        int anchorChunkNo = resolveAnchorChunkNo(hit, sorted);
        String merged = mergeSection(sorted);
        if (merged.length() <= EVIDENCE_CONTEXT_MAX_CHARACTERS) {
            return merged;
        }
        return expandOutwardFromAnchor(sorted, anchorChunkNo, EVIDENCE_CONTEXT_MAX_CHARACTERS);
    }

    private static int resolveAnchorChunkNo(
            KnowledgeVectorStore.SearchCandidate hit, List<KnowledgeSectionChunkSlice> sorted) {
        String hitContent = hit.content() == null ? "" : hit.content();
        for (KnowledgeSectionChunkSlice slice : sorted) {
            if (hitContent.equals(slice.content())) {
                return slice.chunkNo();
            }
        }
        UUID hitChunkId = hit.chunkId();
        for (KnowledgeSectionChunkSlice slice : sorted) {
            if (slice.content().contains(hitContent) || hitContent.contains(slice.content())) {
                return slice.chunkNo();
            }
        }
        return sorted.get(0).chunkNo();
    }

    private static String mergeSection(List<KnowledgeSectionChunkSlice> sorted) {
        StringBuilder builder = new StringBuilder();
        for (KnowledgeSectionChunkSlice slice : sorted) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(slice.content());
        }
        return builder.toString();
    }

    /**
     * 从命中 chunk 向两侧交替纳入相邻 chunk_no，直到再加会超出 maxCharacters。
     */
    static String expandOutwardFromAnchor(
            List<KnowledgeSectionChunkSlice> sorted, int anchorChunkNo, int maxCharacters) {
        int anchorIndex = -1;
        for (int index = 0; index < sorted.size(); index++) {
            if (sorted.get(index).chunkNo() == anchorChunkNo) {
                anchorIndex = index;
                break;
            }
        }
        if (anchorIndex < 0) {
            return truncate(mergeSection(sorted), maxCharacters);
        }
        List<Integer> included = new ArrayList<>();
        included.add(anchorIndex);
        int left = anchorIndex - 1;
        int right = anchorIndex + 1;
        while (true) {
            boolean added = false;
            if (left >= 0 && joinIndexList(sorted, withIndex(included, left)).length() <= maxCharacters) {
                included.add(left);
                left--;
                added = true;
            }
            if (right < sorted.size()
                    && joinIndexList(sorted, withIndex(included, right)).length() <= maxCharacters) {
                included.add(right);
                right++;
                added = true;
            }
            if (!added) {
                break;
            }
        }
        included.sort(Integer::compareTo);
        String result = joinIndexList(sorted, included);
        if (result.length() > maxCharacters) {
            return truncate(result, maxCharacters);
        }
        return result;
    }

    private static List<Integer> withIndex(List<Integer> included, int index) {
        List<Integer> copy = new ArrayList<>(included);
        copy.add(index);
        return copy;
    }

    private static String joinIndexList(List<KnowledgeSectionChunkSlice> sorted, List<Integer> indices) {
        StringBuilder builder = new StringBuilder();
        for (int index : indices) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(sorted.get(index).content());
        }
        return builder.toString();
    }

    private static String truncate(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxCharacters);
    }
}
