package com.insightflow.investigation.window;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 已围绕某条 Alert 冻结的调查时间窗口。
 *
 * <p>所有范围使用左闭右开语义 {@code [start, end)}，相邻 current/previous 窗口不会重复计数。
 * 该值对象不保存当前系统时间，重试时复用同一值即可得到相同证据范围。</p>
 */
public record InvestigationWindow(
        InvestigationWindowType type,
        OffsetDateTime anchorTime,
        OffsetDateTime currentStart,
        OffsetDateTime currentEnd,
        OffsetDateTime previousStart,
        OffsetDateTime previousEnd) {

    /** 构造时拒绝缺失或倒置边界，避免把不可信范围传入仓储查询。 */
    public InvestigationWindow {
        Objects.requireNonNull(type, "窗口类型不能为空");
        Objects.requireNonNull(anchorTime, "告警锚点不能为空");
        requireRange(currentStart, currentEnd, "当前窗口");
        requireRange(previousStart, previousEnd, "对照窗口");
    }

    /** 当前窗口与对照窗口必须首尾相接，确保没有覆盖或缺口。 */
    private static void requireRange(OffsetDateTime start, OffsetDateTime end, String name) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException(name + "时间边界不合法");
        }
    }
}
