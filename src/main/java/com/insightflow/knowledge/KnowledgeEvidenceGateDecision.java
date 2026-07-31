package com.insightflow.knowledge;

import java.util.List;

/**
 * 后验证据门控的确定性判定结果。
 *
 * <p>门控在检索完成后、注入 Prompt 前执行；{@code outcome} 是评测计算弃权率的唯一信号，
 * 不依赖模型输出解析。ABSTAIN 时 {@code injected} 必为空列表。</p>
 */
public record KnowledgeEvidenceGateDecision(
        /** INJECT 或 ABSTAIN。 */
        String outcome,
        /** 通过阈值、实际注入 Prompt 的候选；ABSTAIN 时为空。 */
        List<KnowledgeVectorStore.SearchCandidate> injected,
        /** 门控前的候选数（通常为 Top8 选择结果条数）。 */
        int inputCount,
        /** Top1 分数；ABSTAIN 判定的依据，写入诊断便于调阈值。 */
        double topScore) {

    public static final String OUTCOME_INJECT = "INJECT";
    public static final String OUTCOME_ABSTAIN = "ABSTAIN";

    public KnowledgeEvidenceGateDecision {
        injected = List.copyOf(injected);
    }

    /** 门控关闭或跳过时的恒等决策：原样注入全部候选。 */
    public static KnowledgeEvidenceGateDecision injectAll(
            List<KnowledgeVectorStore.SearchCandidate> candidates) {
        double top = candidates.isEmpty() ? 0.0d : candidates.get(0).score();
        return new KnowledgeEvidenceGateDecision(OUTCOME_INJECT, candidates, candidates.size(), top);
    }

    /** 检索无候选时的弃权决策。 */
    public static KnowledgeEvidenceGateDecision abstainEmpty() {
        return new KnowledgeEvidenceGateDecision(OUTCOME_ABSTAIN, List.of(), 0, 0.0d);
    }
}
