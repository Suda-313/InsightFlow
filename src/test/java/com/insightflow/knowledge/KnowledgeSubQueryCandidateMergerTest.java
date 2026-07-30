package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 多路子查询候选 RRF 合并：去重、保留更高融合分。 */
class KnowledgeSubQueryCandidateMergerTest {

    private static final UUID DOC_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID VER_A = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CHUNK_A = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CHUNK_B = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID CHUNK_SHARED = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Test
    void mergesDistinctChunksFromSubQueries() {
        KnowledgeSearchResult subA = new KnowledgeSearchResult(
                List.of(candidate(CHUNK_A, 0.04d), candidate(CHUNK_SHARED, 0.03d)),
                Set.of(CHUNK_A),
                Set.of(),
                Set.of(CHUNK_SHARED));
        KnowledgeSearchResult subB = new KnowledgeSearchResult(
                List.of(candidate(CHUNK_B, 0.05d), candidate(CHUNK_SHARED, 0.02d)),
                Set.of(),
                Set.of(CHUNK_B),
                Set.of(CHUNK_SHARED));

        KnowledgeSearchResult merged = KnowledgeSubQueryCandidateMerger.merge(List.of(subA, subB), 50);

        assertThat(merged.candidates()).extracting(KnowledgeVectorStore.SearchCandidate::chunkId)
                .containsExactly(CHUNK_SHARED, CHUNK_B, CHUNK_A);
        assertThat(merged.lexicalOnlyChunkIds()).contains(CHUNK_A);
        assertThat(merged.vectorOnlyChunkIds()).contains(CHUNK_B);
        assertThat(merged.bothSourceChunkIds()).containsExactly(CHUNK_SHARED);
    }

    @Test
    void singleSubQueryPassthrough() {
        KnowledgeSearchResult single = new KnowledgeSearchResult(
                List.of(candidate(CHUNK_A, 0.1d)), Set.of(), Set.of(CHUNK_A), Set.of());

        KnowledgeSearchResult merged = KnowledgeSubQueryCandidateMerger.merge(List.of(single), 50);

        assertThat(merged.candidates()).hasSize(1);
        assertThat(merged.candidates().get(0).chunkId()).isEqualTo(CHUNK_A);
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(UUID chunkId, double score) {
        return new KnowledgeVectorStore.SearchCandidate(
                DOC_A, VER_A, 1, chunkId, "title", "content", score, "OPERATION_EVENT", "section", null);
    }
}
