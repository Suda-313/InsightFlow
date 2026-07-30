package com.insightflow.service.analysis;

/**
 * Pack 级 LLM Topic Skill 的调用门控；纯函数，便于单测覆盖 L2×长度组合。
 *
 * <p>仅在规则已零命中（编排层已判定为 topic_general 候选）且全局/Pack 开关开启时，
 * 由本类决定是否值得发起 LLM 调用——优先 complaint/suggestion 且有足够文本，
 * 跳过纯好评与过短评论，控制成本与噪声。</p>
 */
public final class TopicLlmGate {

    private TopicLlmGate() {
    }

    /**
     * 是否应对该条 feedback 调用 Pack LLM Topic Skill。
     *
     * @param expression     已算好的 L2 表达分类
     * @param normalizedText 归一化后的评论文本
     * @param minTextLength  全局配置的最小字符数
     */
    public static boolean shouldInvokeLlm(
            ExpressionClassification expression, String normalizedText, int minTextLength) {
        if (expression == null || normalizedText == null) {
            return false;
        }
        if (normalizedText.length() < minTextLength) {
            return false;
        }
        String l2 = expression.canonicalKey();
        // 纯好评不进 LLM 补标路径，避免把情绪性短评强行贴议题标签。
        if ("expr_praise".equals(l2)) {
            return false;
        }
        return "expr_complaint".equals(l2) || "expr_suggestion".equals(l2);
    }
}
