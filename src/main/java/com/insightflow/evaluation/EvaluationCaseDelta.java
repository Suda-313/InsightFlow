package com.insightflow.evaluation;

/**
 * 同一金标题目在候选批次相对基线批次的规则评分变化。
 *
 * <p>该对象只传递评分字段与变化方向，不携带模型原始输出，避免评测对比接口成为完整回答的重复存储出口。
 * {@code status} 仅表示固定规则下的变化：improved、regressed、mixed、unchanged 或 missing。</p>
 */
public record EvaluationCaseDelta(
        /** 固定金标题目的稳定标识，用于定位 Prompt 变更影响的具体问题。 */
        String caseId,
        /** 题目意图分类，便于按趋势、告警、比较等类别汇总查看。 */
        String category,
        /** 候选相对基线的规则变化方向，不是模型语义质量的绝对判断。 */
        String status,
        /** 候选覆盖的必要事实数减去基线覆盖数，负数表示事实覆盖退化。 */
        Integer coveredRequiredFactDelta,
        /** 候选命中的禁止断言数减去基线命中数，正数表示幻觉风险上升。 */
        Integer hitForbiddenClaimDelta,
        /** 拒答合规从基线到候选的变化；题目不要求拒答时保持 null。 */
        Boolean refusalComplianceChanged,
        /** 回答具体性代理指标从基线到候选的变化；未评分题保持 null。 */
        Boolean answerSpecificChanged) {
}
