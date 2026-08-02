package com.insightflow.service.analysis;

import java.util.List;

/**
 * 一条平台 L2 表达/意图规则；与 {@link IssueRule} 同构但独立成型，因为 L2 是全平台
 * 固定 5 类枚举，不像 L1 那样按 Workspace Pack 动态扩展，混用会让"平台稳定 + Pack
 * 可插拔"的边界在代码层面变得含糊。
 *
 * @param canonicalKey    稳定表达键，如 expr_suggestion；写入 feedback_projection_annotation
 * @param name            用户可读名称，用于 Dashboard 展示
 * @param priority        数值越大越优先；同分命中按 priority 排序决定 primary_expression
 * @param anyPatterns     命中任一即算候选（去重计词）
 * @param excludePatterns 命中任一即整条规则出局，先于正向匹配判定
 */
public record ExpressionRule(
        String canonicalKey,
        String name,
        int priority,
        List<String> anyPatterns,
        List<String> excludePatterns) {
}
