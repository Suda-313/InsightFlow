package com.insightflow.investigation.window;

/**
 * 单个调查证据窗口的粒度类型。
 *
 * <p>BOTH 不是窗口本身：它会在 {@link InvestigationWindowResolver} 中展开为两个独立窗口，
 * 从而让不同粒度的证据拥有各自稳定的标识和时间边界。</p>
 */
public enum InvestigationWindowType {
    /** 告警前连续 24 小时的短期证据范围；日指标只能按可用日桶呈现。 */
    SHORT_TERM,
    /** 告警前连续七天的周级趋势与对照范围。 */
    WEEKLY
}
