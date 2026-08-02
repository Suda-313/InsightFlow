package com.insightflow.knowledge;

import com.insightflow.config.AgentApiKeyPresentCondition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Cross-encoder 精排：对 RRF TopN 候选批量打分，失败或超时回退 {@link RrfOnlyKnowledgeReranker}。
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class CrossEncoderKnowledgeReranker implements KnowledgeReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderKnowledgeReranker.class);

    private final KnowledgeRerankGateway rerankGateway;
    private final RrfOnlyKnowledgeReranker rrfFallback;
    private final int inputLimit;
    private final double rrfWeight;
    private final double diversityPenalty;
    private final String rerankerVersion;

    /**
     * 生产构造：参数全部来自同一个受控 properties，确保排序行为与审计版本标签一致。
     */
    @Autowired
    public CrossEncoderKnowledgeReranker(
            KnowledgeRerankGateway rerankGateway,
            RrfOnlyKnowledgeReranker rrfFallback,
            KnowledgeRerankerProperties properties) {
        this(
                rerankGateway,
                rrfFallback,
                properties.candidateLimit(),
                properties.model(),
                properties.rrfWeight(),
                properties.diversityPenalty());
    }

    /** 测试与兼容入口：保持原来的纯 cross-encoder 排序。 */
    CrossEncoderKnowledgeReranker(
            KnowledgeRerankGateway rerankGateway,
            RrfOnlyKnowledgeReranker rrfFallback,
            int inputLimit,
            String model) {
        this(rerankGateway, rrfFallback, inputLimit, model, 0.0, 0.0);
    }

    /** 离线实验入口：rank fusion 与软多样性可独立开启，避免多变量同时变化。 */
    CrossEncoderKnowledgeReranker(
            KnowledgeRerankGateway rerankGateway,
            RrfOnlyKnowledgeReranker rrfFallback,
            int inputLimit,
            String model,
            double rrfWeight,
            double diversityPenalty) {
        this.rerankGateway = rerankGateway;
        this.rrfFallback = rrfFallback;
        this.inputLimit = Math.max(1, inputLimit);
        this.rrfWeight = clamp(rrfWeight, 0.0, 1.0);
        this.diversityPenalty = Math.max(0.0, diversityPenalty);
        this.rerankerVersion = "knowledge:rerank:" + model
                + ":in" + this.inputLimit
                + ":rrf" + labelNumber(this.rrfWeight)
                + ":div" + labelNumber(this.diversityPenalty);
    }

    @Override
    public KnowledgeRerankOutcome rerank(
            String question, List<KnowledgeVectorStore.SearchCandidate> candidates, int finalLimit) {
        if (candidates.isEmpty()) {
            return rrfFallback.rerank(question, candidates, finalLimit);
        }
        long startedAt = System.nanoTime();
        int rerankInputSize = Math.min(inputLimit, candidates.size());
        List<KnowledgeVectorStore.SearchCandidate> rerankInput = candidates.subList(0, rerankInputSize);
        try {
            List<String> documents = rerankInput.stream()
                    .map(KnowledgeRerankDocumentText::forCandidate)
                    .toList();
            List<KnowledgeRerankGateway.RerankScore> scores = rerankGateway.rerank(
                    question, documents, rerankInputSize);
            List<KnowledgeVectorStore.SearchCandidate> ranked = reorderByScores(rerankInput, scores, finalLimit);
            return new KnowledgeRerankOutcome(
                    ranked,
                    "cross-encoder",
                    rerankerVersion,
                    elapsedMillis(startedAt),
                    false,
                    rerankInputSize);
        } catch (RuntimeException exception) {
            log.warn("knowledge_rerank_fallback reason={}", exception.getMessage());
            KnowledgeRerankOutcome fallback = rrfFallback.rerank(question, candidates, finalLimit);
            return new KnowledgeRerankOutcome(
                    fallback.rankedCandidates(),
                    fallback.rerankerName(),
                    fallback.rerankerVersion(),
                    elapsedMillis(startedAt),
                    true,
                    rerankInputSize);
        }
    }

    private List<KnowledgeVectorStore.SearchCandidate> reorderByScores(
            List<KnowledgeVectorStore.SearchCandidate> rerankInput,
            List<KnowledgeRerankGateway.RerankScore> scores,
            int finalLimit) {
        List<RankedCandidate> pool = buildRankedPool(rerankInput, scores);
        Map<java.util.UUID, Integer> selectedPerDocument = new HashMap<>();
        List<KnowledgeVectorStore.SearchCandidate> selected = new ArrayList<>(finalLimit);
        while (!pool.isEmpty() && selected.size() < finalLimit) {
            RankedCandidate best = pool.stream()
                    .max(Comparator
                            .comparingDouble((RankedCandidate candidate) -> selectionScore(
                                    candidate, selectedPerDocument))
                            .thenComparingInt(candidate -> -candidate.rerankRank())
                            .thenComparingInt(candidate -> -candidate.rrfRank()))
                    .orElseThrow();
            pool.remove(best);
            double finalScore = selectionScore(best, selectedPerDocument);
            selected.add(withRerankScore(best.candidate(), finalScore));
            selectedPerDocument.merge(best.candidate().documentId(), 1, Integer::sum);
        }
        return List.copyOf(selected);
    }

    /**
     * 将供应商分数转换为稳定 rank，再与原 RRF rank 融合。
     *
     * <p>不直接混合原始 score：RRF 与不同 reranker 的分数尺度并不一致。</p>
     */
    private List<RankedCandidate> buildRankedPool(
            List<KnowledgeVectorStore.SearchCandidate> rerankInput,
            List<KnowledgeRerankGateway.RerankScore> scores) {
        List<KnowledgeRerankGateway.RerankScore> validScores = scores.stream()
                .filter(score -> score.index() >= 0 && score.index() < rerankInput.size())
                .sorted(Comparator.comparingDouble(KnowledgeRerankGateway.RerankScore::relevanceScore)
                        .reversed())
                .toList();
        Set<Integer> used = new HashSet<>();
        List<Integer> rerankOrder = new ArrayList<>(rerankInput.size());
        for (KnowledgeRerankGateway.RerankScore score : validScores) {
            if (used.add(score.index())) {
                rerankOrder.add(score.index());
            }
        }
        // 供应商遗漏或重复 index 时，以原 RRF 顺序补齐，不能静默丢候选。
        for (int index = 0; index < rerankInput.size(); index++) {
            if (used.add(index)) {
                rerankOrder.add(index);
            }
        }
        List<RankedCandidate> pool = new ArrayList<>(rerankInput.size());
        for (int rerankIndex = 0; rerankIndex < rerankOrder.size(); rerankIndex++) {
            int candidateIndex = rerankOrder.get(rerankIndex);
            int rerankRank = rerankIndex + 1;
            int rrfRank = candidateIndex + 1;
            double baseScore = (1.0 - rrfWeight) * normalizedRankScore(rerankRank, rerankInput.size())
                    + rrfWeight * normalizedRankScore(rrfRank, rerankInput.size());
            pool.add(new RankedCandidate(rerankInput.get(candidateIndex), rerankRank, rrfRank, baseScore));
        }
        return pool;
    }

    /** 同文档第 2 条起逐步扣分，但不设置不可解释的硬配额。 */
    private double selectionScore(
            RankedCandidate candidate, Map<java.util.UUID, Integer> selectedPerDocument) {
        int sameDocumentCount = selectedPerDocument.getOrDefault(candidate.candidate().documentId(), 0);
        return candidate.baseScore() - diversityPenalty * sameDocumentCount;
    }

    private double normalizedRankScore(int rank, int candidateCount) {
        if (candidateCount <= 1) {
            return 1.0;
        }
        return 1.0 - (double) (rank - 1) / (candidateCount - 1);
    }

    private KnowledgeVectorStore.SearchCandidate withRerankScore(
            KnowledgeVectorStore.SearchCandidate candidate, double rerankScore) {
        return new KnowledgeVectorStore.SearchCandidate(
                candidate.documentId(),
                candidate.versionId(),
                candidate.versionNo(),
                candidate.chunkId(),
                candidate.title(),
                candidate.content(),
                rerankScore,
                candidate.documentType(),
                candidate.sectionHeading(),
                candidate.effectiveWindow());
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String labelNumber(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private record RankedCandidate(
            KnowledgeVectorStore.SearchCandidate candidate,
            int rerankRank,
            int rrfRank,
            double baseScore) {
    }
}
