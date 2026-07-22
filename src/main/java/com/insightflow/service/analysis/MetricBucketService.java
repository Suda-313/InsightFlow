package com.insightflow.service.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.repository.IssueMetricBucketRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 按日聚合分类结果，对 {@code issue_metric_bucket} 执行 UPSERT。
 *
 * <p>同一 (canonicalKey, bucketStart) 内存聚合，再按 workspace + issue + 日桶
 * 查重：已有桶追加 feedback_count 与维度分布；无则新建。</p>
 */
@Component
public class MetricBucketService {

    private final IssueCatalogService catalogService;
    private final IssueMetricBucketRepository bucketRepository;
    private final ObjectMapper objectMapper;

    public MetricBucketService(IssueCatalogService catalogService, IssueMetricBucketRepository bucketRepository,
            ObjectMapper objectMapper) {
        this.catalogService = catalogService;
        this.bucketRepository = bucketRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 将事件分类结果按日聚合并写入指标桶。
     *
     * @param projectionId           当前投影内部主键
     * @param workspaceId            一级租户隔离键
     * @param events                 投影事件列表
     * @param classificationsByEventId 每个事件 ID 对应的分类结果
     * @param canonicalNames         canonicalKey 到可读主题名的映射
     */
    public void write(Long projectionId, Long workspaceId, List<EventInput> events,
            Map<Long, List<Classification>> classificationsByEventId, Map<String, String> canonicalNames) {
        if (events == null || events.isEmpty()) {
            return;
        }

        Map<BucketKey, BucketAggregate> aggregates = new HashMap<>();

        for (EventInput event : events) {
            List<Classification> classifications = classificationsByEventId.get(event.id());
            if (classifications == null || classifications.isEmpty()) {
                continue;
            }

            OffsetDateTime bucketStart = event.occurredAt().toLocalDate().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
            for (Classification classification : classifications) {
                BucketKey key = new BucketKey(classification.canonicalKey(), bucketStart);
                BucketAggregate aggregate = aggregates.computeIfAbsent(key, k -> new BucketAggregate());
                aggregate.feedbackCount++;
                aggregate.sourceKindCounts.merge(event.sourceKind(), 1, Integer::sum);
            }
        }

        for (Map.Entry<BucketKey, BucketAggregate> entry : aggregates.entrySet()) {
            BucketKey key = entry.getKey();
            BucketAggregate aggregate = entry.getValue();
            String canonicalName = canonicalNames.getOrDefault(key.canonicalKey, key.canonicalKey);
            IssueCatalog catalog = catalogService.findOrCreate(workspaceId, key.canonicalKey, canonicalName);
            Long issueId = catalog.getId();

            String dimensionSummaryJson = toJson(aggregate.sourceKindCounts);
            bucketRepository.findByWorkspaceIdAndIssueIdAndBucketStart(workspaceId, issueId, key.bucketStart)
                    .ifPresentOrElse(
                            existing -> {
                                Map<String, Integer> merged = mergeDistributions(
                                        parseJson(existing.getDimensionSummaryJson()),
                                        aggregate.sourceKindCounts);
                                existing.addFeedbackCount(aggregate.feedbackCount, toJson(merged), projectionId);
                            },
                            () -> bucketRepository.save(IssueMetricBucket.of(
                                    workspaceId, issueId, key.bucketStart,
                                    aggregate.feedbackCount, dimensionSummaryJson, projectionId)));
        }
    }

    private Map<String, Integer> mergeDistributions(Map<String, Integer> existing, Map<String, Integer> delta) {
        Map<String, Integer> merged = new TreeMap<>(existing);
        for (Map.Entry<String, Integer> e : delta.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        return merged;
    }

    private Map<String, Integer> parseJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new TreeMap<>();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Integer>>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse dimension_summary_json: " + json, e);
        }
    }

    private String toJson(Map<String, Integer> distribution) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(distribution));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize dimension summary", e);
        }
    }

    private record BucketKey(String canonicalKey, OffsetDateTime bucketStart) {
    }

    private static class BucketAggregate {
        int feedbackCount = 0;
        final Map<String, Integer> sourceKindCounts = new HashMap<>();
    }
}
