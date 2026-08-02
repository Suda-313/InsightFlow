package com.insightflow.service.analysis;

/**
 * 一条反馈对一个主题的关联结果；写入 feedback_issue_link 的 assignment_method 与 confidence。
 *
 * <p>assignment_method 严格区分为 "rule" 与 "ambiguous" 两种语义：
 * "rule" 表示规则正向命中，confidence 封顶为 1.0；
 * "ambiguous" 表示排序后第 1、2 名在 priority+hits 上同分，无法唯一确定，confidence 取 0.5。
 * unclassified 不产生 Classification，调用方收到空列表。</p>
 *
 * @param canonicalKey      关联到的稳定主题键
 * @param confidence        rule 命中=1.0（确定性封顶）；ambiguous=0.5（同分并列）
 * @param assignmentMethod  "rule" 或 "ambiguous"；unclassified 不产生 Classification
 */
public record Classification(String canonicalKey, double confidence, String assignmentMethod) {
}
