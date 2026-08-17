package com.insightflow.investigation.window;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 仅由服务端计算 Investigation 的真实时间边界。
 *
 * <p>输入只能是不可变 Alert 锚点和枚举白名单选择，不接受调用方提供的自由日期，
 * 以保证重试、恢复和人工复核均围绕同一异常事实。</p>
 */
@Component
public class InvestigationWindowResolver {

    /** 将选择展开为稳定顺序的一个或两个窗口。 */
    public List<InvestigationWindow> resolve(OffsetDateTime anchorTime, InvestigationWindowSelection selection) {
        Objects.requireNonNull(anchorTime, "告警锚点不能为空");
        return switch (Objects.requireNonNull(selection, "窗口选择不能为空")) {
            case SHORT_TERM -> List.of(shortTerm(anchorTime));
            case WEEKLY -> List.of(weekly(anchorTime));
            case BOTH -> List.of(shortTerm(anchorTime), weekly(anchorTime));
        };
    }

    /**
     * 生成包含触发日桶的两个连续 24 小时窗口。
     *
     * <p>Alert 锚点是 UTC 日桶起点，而非窗口右边界；先推进一个日桶，才能让
     * {@code [currentStart, currentEnd)} 覆盖触发当天的日指标与反馈样本。</p>
     */
    private InvestigationWindow shortTerm(OffsetDateTime anchor) {
        OffsetDateTime currentEnd = anchor.plusDays(1);
        OffsetDateTime currentStart = anchor;
        return new InvestigationWindow(
                InvestigationWindowType.SHORT_TERM,
                anchor,
                currentStart,
                currentEnd,
                currentStart.minusHours(24),
                currentStart);
    }

    /**
     * 生成包含触发日桶的两个连续七天窗口。
     *
     * <p>日桶使用 UTC 00:00 起点，故当前窗口右端取锚点次日 00:00；previous 与
     * current 在 currentStart 相接，不重叠也不留缺口。</p>
     */
    private InvestigationWindow weekly(OffsetDateTime anchor) {
        OffsetDateTime currentEnd = anchor.plusDays(1);
        OffsetDateTime currentStart = currentEnd.minusDays(7);
        return new InvestigationWindow(
                InvestigationWindowType.WEEKLY,
                anchor,
                currentStart,
                currentEnd,
                currentStart.minusDays(7),
                currentStart);
    }
}
