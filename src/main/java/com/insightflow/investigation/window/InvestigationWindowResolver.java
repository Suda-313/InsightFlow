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

    /** 生成告警前两个连续的 24 小时窗口。 */
    private InvestigationWindow shortTerm(OffsetDateTime anchor) {
        OffsetDateTime currentStart = anchor.minusHours(24);
        return new InvestigationWindow(
                InvestigationWindowType.SHORT_TERM,
                anchor,
                currentStart,
                anchor,
                currentStart.minusHours(24),
                currentStart);
    }

    /** 生成告警前两个连续的七天窗口。 */
    private InvestigationWindow weekly(OffsetDateTime anchor) {
        OffsetDateTime currentStart = anchor.minusDays(7);
        return new InvestigationWindow(
                InvestigationWindowType.WEEKLY,
                anchor,
                currentStart,
                anchor,
                currentStart.minusDays(7),
                currentStart);
    }
}
