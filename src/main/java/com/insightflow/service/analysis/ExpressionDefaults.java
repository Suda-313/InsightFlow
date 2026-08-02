package com.insightflow.service.analysis;

/**
 * L2 平台表达层的兜底常量。
 *
 * <p>与 {@link TopicPackDefaults#TOPIC_GENERAL_KEY} 对称：L1 零命中写 topic_general，
 * L2 零命中写 expr_other——两者都是"正常统计桶"而不是失败标记，都不进人工复核
 * （spec §3.3：L2 不走人工复核，不确定即计入 expr_other 统计）。</p>
 */
public final class ExpressionDefaults {

    /** 规则无法判断时的兜底表达键：过短、纯表情或无法归入其余 4 类的文本。 */
    public static final String EXPR_OTHER_KEY = "expr_other";

    /** 用户可读展示名，与平台 expression-rules.toml 的其余类目风格一致。 */
    public static final String EXPR_OTHER_NAME = "其他";

    /** expression_method 在 v1 固定为 rule；不存在 L1 那样的 general/ambiguous 细分方法。 */
    public static final String EXPRESSION_METHOD = "rule";

    private ExpressionDefaults() {
    }

    /** 构造零命中时的 expr_other 分类结果；confidence=1.0 表示"确定为其他"而非不确定。 */
    public static ExpressionClassification otherClassification() {
        return new ExpressionClassification(EXPR_OTHER_KEY, 1.0, false);
    }
}
