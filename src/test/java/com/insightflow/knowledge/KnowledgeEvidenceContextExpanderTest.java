package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Small-to-big 合并规则：整段 section 优先，超限则从命中 chunk 向外扩展。 */
class KnowledgeEvidenceContextExpanderTest {

    @Test
    void usesFullSectionWhenWithinLimit() {
        List<KnowledgeSectionChunkSlice> slices = List.of(
                slice(1, "导语"),
                slice(2, "正文A"),
                slice(3, "正文B"));
        KnowledgeVectorStore.SearchCandidate hit = hit(2, "正文A");

        String expanded = KnowledgeEvidenceContextExpander.expandForHit(hit, slices);

        assertThat(expanded).isEqualTo("导语\n正文A\n正文B");
    }

    @Test
    void expandsOutwardFromAnchorWhenSectionExceedsLimit() {
        String partA = "A".repeat(400);
        String partB = "B".repeat(400);
        String partC = "C".repeat(400);
        List<KnowledgeSectionChunkSlice> slices = List.of(slice(1, partA), slice(2, partB), slice(3, partC));
        KnowledgeVectorStore.SearchCandidate hit = hit(2, partB);

        String expanded = KnowledgeEvidenceContextExpander.expandForHit(hit, slices);

        assertThat(expanded).isEqualTo(partA + "\n" + partB);
        assertThat(expanded).hasSize(801);
    }

    @Test
    void fallsBackToHitContentWhenSectionMissing() {
        KnowledgeVectorStore.SearchCandidate hit = hit(5, "仅命中段");

        String expanded = KnowledgeEvidenceContextExpander.expandForHit(hit, List.of());

        assertThat(expanded).isEqualTo("仅命中段");
    }

    @Test
    void truncatesOversizedSingleChunk() {
        String longBody = "段".repeat(1200);
        KnowledgeVectorStore.SearchCandidate hit = hit(1, longBody);

        String expanded = KnowledgeEvidenceContextExpander.expandForHit(hit, List.of(slice(1, longBody)));

        assertThat(expanded).hasSize(KnowledgeEvidenceContextExpander.EVIDENCE_CONTEXT_MAX_CHARACTERS);
    }

    private static KnowledgeSectionChunkSlice slice(int chunkNo, String content) {
        return new KnowledgeSectionChunkSlice(chunkNo, content, "KI-1301");
    }

    private static KnowledgeVectorStore.SearchCandidate hit(int chunkNoHint, String content) {
        return new KnowledgeVectorStore.SearchCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "热修复说明",
                content,
                0.5d,
                "KNOWN_ISSUE",
                "KI-1301",
                null);
    }
}
