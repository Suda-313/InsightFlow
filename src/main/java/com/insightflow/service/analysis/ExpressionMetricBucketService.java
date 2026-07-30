package com.insightflow.service.analysis;

import com.insightflow.entity.ExpressionMetricBucket;
import com.insightflow.repository.ExpressionMetricBucketRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按日聚合 L2 表达分类结果，对 {@code expression_metric_bucket} 执行 UPSERT。
 *
 * <p>与 {@link MetricBucketService}（L1）同构但更简单：L2 是全平台固定 5 类枚举，
 * 不需要经过 IssueCatalogService 解析动态目录，也不需要按 source_kind 维度展开
 * dimension_summary_json——Dashboard 首屏只需要"每日每类目多少条"这一最小事实。</p>
 */
@Component
public class ExpressionMetricBucketService {

    private final ExpressionMetricBucketRepository bucketRepository;

    public ExpressionMetricBucketService(ExpressionMetricBucketRepository bucketRepository) {
        this.bucketRepository = bucketRepository;
    }

    /**
     * 将事件的 L2 分类结果按日聚合并写入指标桶。
     *
     * @param projectionId          当前投影内部主键
     * @param workspaceId           一级租户隔离键
     * @param events                投影事件列表
     * @param expressionsByEventId  每个事件 id 对应的 L2 分类结果
     */
    public void write(Long projectionId, Long workspaceId, List<EventInput> events,
            Map<Long, ExpressionClassification> expressionsByEventId) {
        if (events == null || events.isEmpty()) {
            return;
        }

        Map<BucketKey, Integer> counts = new HashMap<>();
        for (EventInput event : events) {
            ExpressionClassification expression = expressionsByEventId.get(event.id());
            if (expression == null) {
                continue;
            }
            OffsetDateTime bucketStart = event.occurredAt().toLocalDate().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
            BucketKey key = new BucketKey(expression.canonicalKey(), bucketStart);
            counts.merge(key, 1, Integer::sum);
        }

        for (Map.Entry<BucketKey, Integer> entry : counts.entrySet()) {
            BucketKey key = entry.getKey();
            int delta = entry.getValue();
            bucketRepository.findByWorkspaceIdAndPrimaryExpressionAndBucketStart(workspaceId, key.primaryExpression, key.bucketStart)
                    .ifPresentOrElse(
                            existing -> existing.addFeedbackCount(delta, projectionId),
                            () -> bucketRepository.save(ExpressionMetricBucket.of(
                                    workspaceId, key.primaryExpression, key.bucketStart, delta, projectionId)));
        }
    }

    private record BucketKey(String primaryExpression, OffsetDateTime bucketStart) {
    }
}
