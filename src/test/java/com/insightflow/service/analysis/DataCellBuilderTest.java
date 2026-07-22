package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * DataCell 切分按 40 条/60 分钟/6000 token 三护栏关闭；空输入返回空列表。
 */
class DataCellBuilderTest {

    /** 41 条事件应在第 40 条关闭第一个 Cell，close_reason=count_limit。 */
    @Test
    void closesOnCountLimit() {
        OffsetDateTime base = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        List<EventInput> events = IntStream.range(0, 41)
                .mapToObj(i -> new EventInput((long) i, base.plusSeconds(i), "工单", "短文本"))
                .toList();
        DataCellBuilder builder = new DataCellBuilder(40, 60, 6000);

        List<DataCellPlan> cells = builder.split(events);

        assertThat(cells).hasSize(2);
        assertThat(cells.get(0).closeReason()).isEqualTo("count_limit");
        assertThat(cells.get(0).events()).hasSize(40);
        assertThat(cells.get(1).closeReason()).isEqualTo("stream_end");
    }

    /** 事件跨过 60 分钟应在窗口边界关闭 Cell。 */
    @Test
    void closesOnWindowLimit() {
        OffsetDateTime base = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        List<EventInput> events = List.of(
                new EventInput(1L, base, "工单", "x"),
                new EventInput(2L, base.plusMinutes(61), "工单", "y"));
        DataCellBuilder builder = new DataCellBuilder(40, 60, 6000);

        List<DataCellPlan> cells = builder.split(events);

        assertThat(cells).hasSize(2);
        assertThat(cells.get(0).closeReason()).isEqualTo("window_limit");
    }

    /** 空输入返回空列表，不创建空 Cell。 */
    @Test
    void emptyInputReturnsEmpty() {
        DataCellBuilder builder = new DataCellBuilder(40, 60, 6000);

        assertThat(builder.split(List.of())).isEmpty();
    }

    /** 单条事件 token 超 budget 应独占一个 Cell 并标 token_limit，即使它在流末尾。 */
    @Test
    void singleOverBudgetEventGetsTokenLimit() {
        OffsetDateTime base = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        // 构造一条 token 远超 6000 的事件：9000 个 CJK 字符 → cjk=9000 → tokens=(int)(9000/1.5)+1=6001
        String huge = "啊".repeat(9000);
        List<EventInput> events = List.of(new EventInput(1L, base, "工单", huge));
        DataCellBuilder builder = new DataCellBuilder(40, 60, 6000);

        List<DataCellPlan> cells = builder.split(events);

        assertThat(cells).hasSize(1);
        assertThat(cells.get(0).closeReason()).isEqualTo("token_limit");
        assertThat(cells.get(0).events()).hasSize(1);
    }
}
