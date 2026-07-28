package com.insightflow.service.analysis;

/**
 * Workspace Topic Pack 内置兜底议题常量。
 *
 * <p>规则零命中时不进复核队列，而是写入 {@link #TOPIC_GENERAL_KEY} 议题 link，
 * 作为正常 L1 统计桶参与趋势与钻取；alert_eligible 默认 false，不参与 EWMA 告警。</p>
 */
public final class TopicPackDefaults {

    /** Pack 内置综合议题 canonical_key；零 L1 规则命中时由投影层写入。 */
    public static final String TOPIC_GENERAL_KEY = "topic_general";

    /** 用户可读展示名，与 Pack catalog 约定一致。 */
    public static final String TOPIC_GENERAL_NAME = "综合/未指向";

    /** assignment_method 取值：规则未命中时的 GENERAL 出口，区别于 rule / ambiguous。 */
    public static final String ASSIGNMENT_GENERAL = "general";

    private TopicPackDefaults() {
    }

    /**
     * 构造零命中时的 topic_general 分类结果，供投影编排层统一使用。
     */
    public static Classification generalClassification() {
        return new Classification(TOPIC_GENERAL_KEY, 1.0, ASSIGNMENT_GENERAL);
    }
}
