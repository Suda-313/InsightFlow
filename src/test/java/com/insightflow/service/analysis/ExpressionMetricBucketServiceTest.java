package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.insightflow.entity.ExpressionMetricBucket;
import com.insightflow.repository.ExpressionMetricBucketRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ExpressionMetricBucketService 按日聚合 L2 分类结果并 UPSERT 写入 expression_metric_bucket。 */
class ExpressionMetricBucketServiceTest {

    private final ExpressionMetricBucketRepository bucketRepository = mock(ExpressionMetricBucketRepository.class);
    private final ExpressionMetricBucketService service = new ExpressionMetricBucketService(bucketRepository);

    private final Long workspaceId = 7L;
    private final Long projectionId = 42L;

    private static OffsetDateTime utcDate(String iso) {
        return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** 单事件单类目，bucket 计数=1。 */
    @Test
    void writesSingleBucketForOneEvent() {
        EventInput event = new EventInput(1L, utcDate("2026-07-20T10:00:00Z"), "工单", "希望优化");
        Map<Long, ExpressionClassification> expressions = Map.of(
                1L, new ExpressionClassification("expr_suggestion", 1.0, false));

        service.write(projectionId, workspaceId, List.of(event), expressions);

        verify(bucketRepository).save(argThat((ExpressionMetricBucket bucket) ->
                bucket.getWorkspaceId().equals(workspaceId)
                        && bucket.getPrimaryExpression().equals("expr_suggestion")
                        && bucket.getBucketStart().equals(utcDate("2026-07-20T00:00:00Z"))
                        && bucket.getFeedbackCount() == 1
                        && bucket.getWorkspaceProjectionId().equals(projectionId)));
    }

    /** 同日同类目多事件合并计数。 */
    @Test
    void mergesMultipleEventsIntoSameBucket() {
        List<EventInput> events = List.of(
                new EventInput(1L, utcDate("2026-07-20T09:00:00Z"), "工单", "好评1"),
                new EventInput(2L, utcDate("2026-07-20T11:00:00Z"), "评价", "好评2"));
        Map<Long, ExpressionClassification> expressions = Map.of(
                1L, new ExpressionClassification("expr_praise", 1.0, false),
                2L, new ExpressionClassification("expr_praise", 1.0, false));

        service.write(projectionId, workspaceId, events, expressions);

        verify(bucketRepository).save(argThat((ExpressionMetricBucket bucket) -> bucket.getFeedbackCount() == 2));
    }

    /** 已有桶追加，feedbackCount 从 5→6，不重复 save。 */
    @Test
    void upsertsWhenBucketExists() {
        OffsetDateTime bucketStart = utcDate("2026-07-20T00:00:00Z");
        ExpressionMetricBucket existing = ExpressionMetricBucket.of(workspaceId, "expr_other", bucketStart, 5, projectionId);
        when(bucketRepository.findByWorkspaceIdAndPrimaryExpressionAndBucketStart(workspaceId, "expr_other", bucketStart))
                .thenReturn(Optional.of(existing));
        EventInput event = new EventInput(1L, utcDate("2026-07-20T10:00:00Z"), "评价", "233");
        Map<Long, ExpressionClassification> expressions = Map.of(
                1L, ExpressionDefaults.otherClassification());

        service.write(projectionId, workspaceId, List.of(event), expressions);

        assertThat(existing.getFeedbackCount()).isEqualTo(6);
        verify(bucketRepository, never()).save(any(ExpressionMetricBucket.class));
    }

    /** 空输入不抛异常不写。 */
    @Test
    void emptyInputReturnsWithoutWriting() {
        service.write(projectionId, workspaceId, List.of(), Map.of());

        verify(bucketRepository, never()).save(any(ExpressionMetricBucket.class));
    }
}
