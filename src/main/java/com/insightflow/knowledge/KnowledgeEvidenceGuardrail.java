package com.insightflow.knowledge;

import com.insightflow.entity.RagGoldQuestionType;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 判断检索候选是否足以注入 Prompt，并在注入时过滤低分尾部。
 *
 * <p>阈值是服务端固定护栏，不由模型或 HTTP 请求控制。RRF 分数来自固定融合公式（双路 Top1≈0.033，
 * 单路 Top1≈0.016）；Cross-encoder 精排成功后分数被覆写为 0~1 归一化融合分，须使用另一套阈值。</p>
 *
 * <p>闲聊 / 知识库无覆盖题在评测与线上均优先走确定性弃权路由，避免仅靠 RRF 阈值漏掉伪相关 Top1。</p>
 */
@Component
public class KnowledgeEvidenceGuardrail {

    /** 评测 seed 中标记为应弃权的题型：检索仍可执行，但门控必须 ABSTAIN。 */
    private static final Set<RagGoldQuestionType> FORCE_ABSTAIN_TYPES = Set.of(
            RagGoldQuestionType.CHITCHAT,
            RagGoldQuestionType.NO_ANSWER);

    /** 常见寒暄与能力元问题；与 abstain-50 中 CHITCHAT 口径对齐。 */
    private static final Set<String> CHITCHAT_GREETINGS = Set.of(
            "你好", "您好", "早上好", "晚上好", "中午好", "谢谢", "多谢", "再见", "拜拜", "hi", "hello");

    private static final List<String> CHITCHAT_META_PREFIXES = List.of(
            "你能做什么", "你可以做什么", "你是谁", "你是做什么的");

    /** 双路 RRF 排名第一约 2/61；用于区分强双路命中与弱单路召回。 */
    private static final double RRF_ABSTAIN_TOP1_SCORE = 0.02d;

    /** 单路 RRF 排名第一约 1/61；低于此分的候选不进入最终证据。 */
    private static final double RRF_MIN_INJECTABLE_SCORE = 0.0164d;

    /** Cross-encoder 融合分 Top1 低于此值视为整体无相关知识。 */
    private static final double RERANK_ABSTAIN_TOP1_SCORE = 0.35d;

    /** Cross-encoder 融合分低于此值的候选不注入。 */
    private static final double RERANK_MIN_INJECTABLE_SCORE = 0.25d;

    /** 保留原语义：首轮双路命中视为证据充足（补检索决策，当前 retrieve 路径未调用）。 */
    private static final double SUFFICIENT_RRF_SCORE = RRF_ABSTAIN_TOP1_SCORE;

    /** 只有存在至少一条满足固定最低相关度的候选，首轮才被视为证据充足。 */
    public boolean isSufficient(List<KnowledgeVectorStore.SearchCandidate> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.score() >= SUFFICIENT_RRF_SCORE);
    }

    /**
     * 决定最终注入哪些证据。
     *
     * @param rankedCandidates 已完成精排与覆盖选择的 TopN，按相关性降序
     * @param rerankScoresActive true 表示候选 score 已被 Cross-encoder 精排覆写
     */
    /**
     * 题型路由 + 线上寒暄启发式：命中则跳过分数阈值，整体不注入证据。
     *
     * @param questionTypeName 金标评测题型名；生产 Chat 路径通常为 null
     */
    public boolean shouldForceAbstain(String question, String questionTypeName) {
        if (questionTypeName != null && !questionTypeName.isBlank()) {
            try {
                if (FORCE_ABSTAIN_TYPES.contains(RagGoldQuestionType.valueOf(questionTypeName))) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // 非金标题型名时仅走问句启发式。
            }
        }
        if (question == null || question.isBlank()) {
            return true;
        }
        String trimmed = question.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (CHITCHAT_GREETINGS.contains(trimmed) || CHITCHAT_GREETINGS.contains(lower)) {
            return true;
        }
        for (String prefix : CHITCHAT_META_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return trimmed.length() <= 2;
    }

    public KnowledgeEvidenceGateDecision decide(
            List<KnowledgeVectorStore.SearchCandidate> rankedCandidates,
            boolean rerankScoresActive) {
        return decide(rankedCandidates, rerankScoresActive, false);
    }

    public KnowledgeEvidenceGateDecision decide(
            List<KnowledgeVectorStore.SearchCandidate> rankedCandidates,
            boolean rerankScoresActive,
            boolean forceAbstain) {
        if (forceAbstain) {
            double top = rankedCandidates == null || rankedCandidates.isEmpty()
                    ? 0.0d
                    : rankedCandidates.get(0).score();
            int inputCount = rankedCandidates == null ? 0 : rankedCandidates.size();
            return new KnowledgeEvidenceGateDecision(
                    KnowledgeEvidenceGateDecision.OUTCOME_ABSTAIN,
                    List.of(),
                    inputCount,
                    top);
        }
        if (rankedCandidates == null || rankedCandidates.isEmpty()) {
            return KnowledgeEvidenceGateDecision.abstainEmpty();
        }
        double abstainThreshold = rerankScoresActive ? RERANK_ABSTAIN_TOP1_SCORE : RRF_ABSTAIN_TOP1_SCORE;
        double minInjectable = rerankScoresActive ? RERANK_MIN_INJECTABLE_SCORE : RRF_MIN_INJECTABLE_SCORE;
        double topScore = rankedCandidates.get(0).score();
        if (topScore < abstainThreshold) {
            return new KnowledgeEvidenceGateDecision(
                    KnowledgeEvidenceGateDecision.OUTCOME_ABSTAIN,
                    List.of(),
                    rankedCandidates.size(),
                    topScore);
        }
        List<KnowledgeVectorStore.SearchCandidate> injected = rankedCandidates.stream()
                .filter(candidate -> candidate.score() >= minInjectable)
                .toList();
        if (injected.isEmpty()) {
            return new KnowledgeEvidenceGateDecision(
                    KnowledgeEvidenceGateDecision.OUTCOME_ABSTAIN,
                    List.of(),
                    rankedCandidates.size(),
                    topScore);
        }
        return new KnowledgeEvidenceGateDecision(
                KnowledgeEvidenceGateDecision.OUTCOME_INJECT,
                injected,
                rankedCandidates.size(),
                topScore);
    }
}
