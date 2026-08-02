package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CrossEncoderKnowledgeRerankerTest {

    @Mock private KnowledgeRerankGateway rerankGateway;

    @Test
    void reordersCandidatesByRerankScores() {
        UUID chunkA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> candidates = List.of(
                candidate(chunkA, 0.9d, "A"),
                candidate(chunkB, 0.1d, "B"));
        when(rerankGateway.rerank(eq("问题"), anyList(), anyInt()))
                .thenReturn(List.of(
                        new KnowledgeRerankGateway.RerankScore(1, 0.95d),
                        new KnowledgeRerankGateway.RerankScore(0, 0.40d)));

        CrossEncoderKnowledgeReranker reranker = new CrossEncoderKnowledgeReranker(
                rerankGateway, new RrfOnlyKnowledgeReranker(), 30, "qwen3-rerank");
        KnowledgeRerankOutcome outcome = reranker.rerank("问题", candidates, 2);

        assertThat(outcome.fallbackUsed()).isFalse();
        assertThat(outcome.rankedCandidates()).extracting(KnowledgeVectorStore.SearchCandidate::chunkId)
                .containsExactly(chunkB, chunkA);
        assertThat(outcome.rerankerName()).isEqualTo("cross-encoder");
    }

    @Test
    void fallsBackToRrfWhenGatewayFails() {
        UUID chunkA = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> candidates = List.of(candidate(chunkA, 0.9d, "A"));
        when(rerankGateway.rerank(eq("问题"), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("api down"));

        CrossEncoderKnowledgeReranker reranker = new CrossEncoderKnowledgeReranker(
                rerankGateway, new RrfOnlyKnowledgeReranker(), 30, "qwen3-rerank");
        KnowledgeRerankOutcome outcome = reranker.rerank("问题", candidates, 1);

        assertThat(outcome.fallbackUsed()).isTrue();
        assertThat(outcome.rankedCandidates()).extracting(KnowledgeVectorStore.SearchCandidate::chunkId)
                .containsExactly(chunkA);
        assertThat(outcome.rerankerName()).isEqualTo("rrf-only");
    }

    @Test
    void sendsConfiguredTopFiftyCandidatesToGateway() {
        List<KnowledgeVectorStore.SearchCandidate> candidates = IntStream.range(0, 50)
                .mapToObj(index -> candidate(UUID.randomUUID(), 1.0d - index / 100.0d, "C" + index))
                .toList();
        List<KnowledgeRerankGateway.RerankScore> scores = IntStream.range(0, 50)
                .mapToObj(index -> new KnowledgeRerankGateway.RerankScore(index, 1.0d - index / 100.0d))
                .toList();
        when(rerankGateway.rerank(eq("问题"), anyList(), eq(50))).thenReturn(scores);

        CrossEncoderKnowledgeReranker reranker = new CrossEncoderKnowledgeReranker(
                rerankGateway, new RrfOnlyKnowledgeReranker(), 50, "qwen3-rerank");
        KnowledgeRerankOutcome outcome = reranker.rerank("问题", candidates, 8);

        assertThat(outcome.inputCandidateCount()).isEqualTo(50);
        assertThat(outcome.rankedCandidates()).hasSize(8);
    }

    @Test
    void rankFusionProtectsStrongRrfAnchorFromRerankReversal() {
        UUID chunkA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> candidates = List.of(
                candidate(chunkA, 0.9d, "RRF first"),
                candidate(chunkB, 0.8d, "RRF second"));
        when(rerankGateway.rerank(eq("问题"), anyList(), anyInt()))
                .thenReturn(List.of(
                        new KnowledgeRerankGateway.RerankScore(1, 0.95d),
                        new KnowledgeRerankGateway.RerankScore(0, 0.40d)));

        CrossEncoderKnowledgeReranker reranker = new CrossEncoderKnowledgeReranker(
                rerankGateway, new RrfOnlyKnowledgeReranker(), 30, "qwen3-rerank", 0.75, 0.0);
        KnowledgeRerankOutcome outcome = reranker.rerank("问题", candidates, 2);

        assertThat(outcome.rankedCandidates()).extracting(KnowledgeVectorStore.SearchCandidate::chunkId)
                .containsExactly(chunkA, chunkB);
    }

    @Test
    void softDiversityKeepsSecondDocumentInFinalSelection() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        List<KnowledgeVectorStore.SearchCandidate> candidates = List.of(
                candidate(documentA, UUID.randomUUID(), 0.9d, "A1"),
                candidate(documentA, UUID.randomUUID(), 0.8d, "A2"),
                candidate(documentA, UUID.randomUUID(), 0.7d, "A3"),
                candidate(documentB, UUID.randomUUID(), 0.6d, "B1"));
        when(rerankGateway.rerank(eq("跨文档问题"), anyList(), anyInt()))
                .thenReturn(List.of(
                        new KnowledgeRerankGateway.RerankScore(0, 0.99d),
                        new KnowledgeRerankGateway.RerankScore(1, 0.98d),
                        new KnowledgeRerankGateway.RerankScore(2, 0.97d),
                        new KnowledgeRerankGateway.RerankScore(3, 0.80d)));

        CrossEncoderKnowledgeReranker reranker = new CrossEncoderKnowledgeReranker(
                rerankGateway, new RrfOnlyKnowledgeReranker(), 30, "qwen3-rerank", 0.0, 0.8);
        KnowledgeRerankOutcome outcome = reranker.rerank("跨文档问题", candidates, 2);

        assertThat(outcome.rankedCandidates()).extracting(KnowledgeVectorStore.SearchCandidate::documentId)
                .containsExactly(documentA, documentB);
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(UUID chunkId, double score, String title) {
        return candidate(UUID.randomUUID(), chunkId, score, title);
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(
            UUID documentId, UUID chunkId, double score, String title) {
        return new KnowledgeVectorStore.SearchCandidate(
                documentId,
                UUID.randomUUID(),
                1,
                chunkId,
                title,
                "body",
                score,
                "SOP",
                "章节",
                "always");
    }
}
