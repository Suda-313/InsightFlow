package com.insightflow.service.analysis;

/**
 * 一条反馈的 L2 表达/意图分类结果；写入 feedback_projection_annotation 的
 * primary_expression / expression_confidence / mixed_expression。
 *
 * <p>与 L1 的 {@link Classification} 不同，L2 每条反馈必有且只有 1 个主标签
 * （至少是 {@link ExpressionDefaults#EXPR_OTHER_KEY}），因此不需要 assignmentMethod
 * 区分 rule/ambiguous/general——expression_method 在 v1 固定为 "rule"，由调用方
 * 写入时直接常量化，不必携带在本记录里。</p>
 *
 * @param canonicalKey     L2 稳定键，如 expr_suggestion / expr_other
 * @param confidence       置信度；唯一命中=1.0，同分并列(mixed)=0.5，零命中兜底=1.0
 * @param mixedExpression  多个意图同现且分差低于阈值（同 priority+hits 并列）时为 true
 */
public record ExpressionClassification(String canonicalKey, double confidence, boolean mixedExpression) {
}
