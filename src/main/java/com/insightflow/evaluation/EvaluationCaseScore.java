package com.insightflow.evaluation;

/**
 * 单条金标题目的确定性规则评分结果。
 *
 * <p>该对象记录事实覆盖、禁止断言和证据引用三类可复现代理指标；它不替代人工对因果判断、
 * 语言质量和引用语义正确性的复核。</p>
 */
public record EvaluationCaseScore(
        /** 金标题目中定义的必含事实总数。 */
        int requiredFactCount,
        /** 回答中被精确命中的必含事实数量。 */
        int coveredRequiredFactCount,
        /** 金标题目中定义的禁止断言总数。 */
        int forbiddenClaimCount,
        /** 回答中被命中的禁止断言数量。 */
        int hitForbiddenClaimCount,
        /** 拒答题是否保留了边界且未命中禁止断言。 */
        boolean refusalCompliant,
        /** 回答是否至少覆盖一项必含事实。 */
        boolean answerSpecific,
        /** 回答是否至少使用一次 P2 证据引用格式；仅验证格式存在。 */
        boolean evidenceCitationPresent) {

    /** 兼容已持久化的 P1 逐题评分 JSON；历史批次没有引用指标时按未引用处理。 */
    public EvaluationCaseScore(
            int requiredFactCount,
            int coveredRequiredFactCount,
            int forbiddenClaimCount,
            int hitForbiddenClaimCount,
            boolean refusalCompliant,
            boolean answerSpecific) {
        this(requiredFactCount, coveredRequiredFactCount, forbiddenClaimCount, hitForbiddenClaimCount,
                refusalCompliant, answerSpecific, false);
    }
}
