package com.insightflow.investigation.window;

/**
 * 默认策略或受控 Planner 可选择的白名单结果。
 *
 * <p>该枚举不携带自由日期；真实边界只能由服务端 Resolver 根据 Alert 锚点计算。</p>
 */
public enum InvestigationWindowSelection {
    SHORT_TERM,
    WEEKLY,
    BOTH
}
