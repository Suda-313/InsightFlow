package com.insightflow.evaluation.rag;

/**
 * 单题检索漏斗指标：Candidate Recall 与候选来源统计。
 */
public record RagGoldCaseRetrievalFunnel(
        boolean candidateDocumentHitAt10,
        boolean candidateDocumentHitAt30,
        boolean candidateDocumentHitAt50,
        boolean candidateChunkHitAt10,
        boolean candidateChunkHitAt30,
        boolean candidateChunkHitAt50,
        boolean crossDocumentDualDocumentHit,
        int lexicalOnlyCandidates,
        int vectorOnlyCandidates,
        int bothSourceCandidates) {
}
