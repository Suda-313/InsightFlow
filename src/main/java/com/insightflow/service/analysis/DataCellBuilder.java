package com.insightflow.service.analysis;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 按条数/时间窗/token 预算三护栏切分有序事件；任一触发即关闭当前 Cell 并开启新 Cell.
 *
 * <p>切分是后续规则分类的输入：把无界的事件流变成有界的 Cell，
 * 每个 Cell 的大小必须受控，否则下游大模型调用或批量分类会失控。</p>
 *
 * <p>三护栏的优先级是 count &gt; window &gt; token，原因如下：
 * <ul>
 *   <li>count_limit 是硬性容量约束，直接对应下游批量接口的最大请求数；</li>
 *   <li>window_limit 保证时间局部性，防止把跨天的事件塞进同一个 Cell 导致语义漂移；</li>
 *   <li>token_limit 是预算软约束，当前两条都未触发但再加一条会超预算时触发。</li>
 * </ul>
 * 因此多护栏同时触发时，按 count &gt; window &gt; token 取原因最符合业务解释。</p>
 *
 * <p>token 估算对齐原型 cell_windowing.estimate_tokens：CJK 约 1.5 字/token，其余约 4 字符/token。
 * 单条事件自身 token 就超过 budget 时，不丢弃该事件，而是让它独占一个 Cell 并标记 close_reason=token_limit，
 * 避免数据丢失，同时让调用方可以识别并单独处理这种极端样本。</p>
 */
public class DataCellBuilder {

    /** 条数护栏；达到即关闭 Cell。 */
    private final int maxCount;

    /** 时间窗护栏（分钟）；span &gt;= maxWindowMinutes 即关闭 Cell。 */
    private final int maxWindowMinutes;

    /** token 预算护栏；累计 &gt; budget 即关闭 Cell。 */
    private final int tokenBudget;

    /** 构造切分器；参数来自配置，禁止运行期改写。 */
    public DataCellBuilder(int maxCount, int maxWindowMinutes, int tokenBudget) {
        this.maxCount = maxCount;
        this.maxWindowMinutes = maxWindowMinutes;
        this.tokenBudget = tokenBudget;
    }

    /**
     * 把已按 occurred_at 升序的事件切分为多个 Cell；空输入返回空列表。
     *
     * <p>遍历事件时维护当前 Cell 的窗口起点、累计 token 和事件列表。
     * 对于每条新事件，如果当前 Cell 已经非空，则检查三护栏：
     * <ul>
     *   <li>条数达到 maxCount；</li>
     *   <li>时间跨度达到 maxWindowMinutes；</li>
     *   <li>加入当前事件后 token 累计超过 tokenBudget。</li>
     * </ul>
     * 任一条件满足就关闭当前 Cell，然后以当前事件开启新 Cell。</p>
     *
     * <p>流末尾剩余的 current Cell 使用 close_reason=stream_end，
     * 表示是自然到达输入流末尾而非被护栏强制关闭。</p>
     */
    public List<DataCellPlan> split(List<EventInput> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<DataCellPlan> cells = new ArrayList<>();
        List<EventInput> current = new ArrayList<>();
        int tokenSum = 0;
        OffsetDateTime windowStart = null;
        for (EventInput event : events) {
            if (!current.isEmpty() && windowStart != null) {
                Duration span = Duration.between(windowStart, event.occurredAt());
                boolean exceedCount = current.size() >= maxCount;
                boolean exceedWindow = span.toMinutes() >= maxWindowMinutes;
                boolean exceedToken = tokenSum + estimateTokens(event.normalizedText()) > tokenBudget;
                if (exceedCount || exceedWindow || exceedToken) {
                    cells.add(toPlan(current, windowStart, pickReason(exceedCount, exceedWindow, exceedToken), tokenSum));
                    current = new ArrayList<>();
                    tokenSum = 0;
                    windowStart = null;
                }
            }
            if (current.isEmpty()) {
                // 新 Cell 的起点是第一条事件的实际发生时间，后续事件的时间窗都相对它计算。
                windowStart = event.occurredAt();
            }
            current.add(event);
            tokenSum += estimateTokens(event.normalizedText());
        }
        if (!current.isEmpty()) {
            // 流末尾剩余的 current Cell 通常以 stream_end 关闭，表示自然到达输入流末尾；
            // 但 spec §4.3 要求：若 tail 仅含单条事件且其自身 token 已超预算，
            // 则它独占一个 Cell 并标 token_limit（与循环内三护栏语义一致），避免数据丢失的同时
            // 让调用方识别并单独处理这种极端样本。多事件 tail 或单事件在预算内仍走 stream_end。
            String tailReason = "stream_end";
            if (current.size() == 1
                    && estimateTokens(current.get(0).normalizedText()) > tokenBudget) {
                tailReason = "token_limit";
            }
            cells.add(toPlan(current, windowStart, tailReason, tokenSum));
        }
        return cells;
    }

    /**
     * 多护栏同时触发时取优先级 count &gt; window &gt; token.
     *
     * <p>优先级的设计与业务语义对齐：
     * count_limit 是最大批次容量，window_limit 是时间局部性，
     * token_limit 只是预算软约束；因此 count 最高，window 次之，token 最低。</p>
     */
    private String pickReason(boolean exceedCount, boolean exceedWindow, boolean exceedToken) {
        if (exceedCount) {
            return "count_limit";
        }
        if (exceedWindow) {
            return "window_limit";
        }
        return "token_limit";
    }

    /** 组装 DataCellPlan；windowEnd 为 Cell 末条事件 occurredAt。 */
    private DataCellPlan toPlan(List<EventInput> events, OffsetDateTime windowStart, String reason, int tokenSum) {
        return new DataCellPlan(
                windowStart,
                events.get(events.size() - 1).occurredAt(),
                reason,
                List.copyOf(events),
                tokenSum);
    }

    /**
     * 估算文本 token：CJK/1.5 + other/4 + 1；空文本返回 1 避免零预算。
     *
     * <p>估算公式来自原型经验值：CJK 字符信息密度高，平均每 1.5 字一个 token；
     * 非 CJK 字符（拉丁字母、数字、符号等）平均每 4 字符一个 token。
     * 加 1 是为了覆盖模型通常添加的句首/特殊 token 开销，防止低估。</p>
     *
     * @param text 归一化文本
     * @return 估算 token 数，至少为 1
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // CJK 统一表意符号基本区范围覆盖中文常见字符。
            if (ch >= '一' && ch <= '鿿') {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return (int) (cjk / 1.5 + other / 4.0) + 1;
    }
}
