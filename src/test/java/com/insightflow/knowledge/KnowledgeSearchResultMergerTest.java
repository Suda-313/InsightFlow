package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeSearchResultMergerTest {

    private static final UUID DOC_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID VER_A = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CHUNK_NARROW = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CHUNK_BROAD = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Test
    void mergeKeepsHighestScoreAndBroadOnlyChunks() {
        KnowledgeSearchResult narrowed = new KnowledgeSearchResult(
                List.of(candidate(CHUNK_NARROW, 0.03d)),
                Set.of(),
                Set.of(CHUNK_NARROW),
                Set.of());
        KnowledgeSearchResult broad = new KnowledgeSearchResult(
                List.of(candidate(CHUNK_NARROW, 0.02d), candidate(CHUNK_BROAD, 0.025d)),
                Set.of(CHUNK_BROAD),
                Set.of(),
                Set.of(CHUNK_NARROW));

        KnowledgeSearchResult merged = KnowledgeSearchResultMerger.merge(narrowed, broad, 50);

        assertThat(merged.candidates()).hasSize(2);
        assertThat(merged.candidates().get(0).chunkId()).isEqualTo(CHUNK_NARROW);
        assertThat(merged.candidates().get(0).score()).isEqualTo(0.03d);
        assertThat(merged.bothSourceChunkIds()).containsExactly(CHUNK_NARROW);
        assertThat(merged.lexicalOnlyChunkIds()).containsExactly(CHUNK_BROAD);
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(UUID chunkId, double score) {
        return new KnowledgeVectorStore.SearchCandidate(
                DOC_A, VER_A, 1, chunkId, "title", "content", score, "RELEASE_NOTE", "section", null);
    }
}
