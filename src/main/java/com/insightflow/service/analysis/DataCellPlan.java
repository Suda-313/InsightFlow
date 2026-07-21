package com.insightflow.service.analysis;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一次投影内一个 Data Cell 的计算输出值；落库前由 ProjectionFactWriter 转为 data_cell + cell_issue.
 *
 * <p>DataCell 通过 count/window/token 三护栏关闭，每个 cell 携带关闭原因、
 * 时间窗边界以及内部事件列表，便于后续规则分类与持久化。</p>
 *
 * @param windowStart     Cell 首条事件 occurred_at
 * @param windowEnd       Cell 末条事件 occurred_at
 * @param closeReason     count_limit/window_limit/token_limit/stream_end
 * @param events          Cell 内事件列表（已按 occurred_at 升序)
 * @param estimatedTokens Cell 内全部事件 token 估算之和
 */
public record DataCellPlan(
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        String closeReason,
        List<EventInput> events,
        int estimatedTokens) {
}
