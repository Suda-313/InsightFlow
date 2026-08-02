package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.repository.IssueMetricBucketRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** MetricBucketService 按日聚合分类结果并 UPSERT 写入 issue_metric_bucket。 */
class MetricBucketServiceTest {

    private final IssueCatalogService catalogService = mock(IssueCatalogService.class);
    private final IssueMetricBucketRepository bucketRepository = mock(IssueMetricBucketRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetricBucketService service = new MetricBucketService(catalogService, bucketRepository, objectMapper);

    private final Long workspaceId = 7L;
    private final Long projectionId = 42L;
    private final Long issueId = 100L;

    private IssueCatalog stubCatalog(String canonicalKey) {
        IssueCatalog catalog = mock(IssueCatalog.class);
        when(catalog.getId()).thenReturn(issueId);
        when(catalogService.findOrCreate(eq(workspaceId), eq(canonicalKey), any()))
                .thenReturn(catalog);
        return catalog;
    }

    private static OffsetDateTime utcDate(String iso) {
        return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** 单事件单主题，bucket 计数=1。 */
    @Test
    void writesSingleBucketForOneEventOneIssue() {
        String canonicalKey = "login_failure";
        stubCatalog(canonicalKey);
        EventInput event = new EventInput(1L, utcDate("2026-07-20T10:00:00Z"), "工单", "无法登录");
        Map<Long, List<Classification>> classificationsByEventId = Map.of(
                1L, List.of(new Classification(canonicalKey, 1.0, "rule")));
        Map<String, String> canonicalNames = Map.of(canonicalKey, "登录失败");

        service.write(projectionId, workspaceId, List.of(event), classificationsByEventId, canonicalNames);

        verify(bucketRepository).save(bucketArgMatcher(workspaceId, issueId, utcDate("2026-07-20T00:00:00Z"), 1, "{\"工单\":1}" , projectionId));
    }

    /** 同日同主题多事件合并，feedback_count=3。 */
    @Test
    void mergesMultipleEventsIntoSameBucket() {
        String canonicalKey = "slow_query";
        stubCatalog(canonicalKey);
        List<EventInput> events = List.of(
                new EventInput(1L, utcDate("2026-07-20T09:00:00Z"), "工单", "查询慢"),
                new EventInput(2L, utcDate("2026-07-20T11:00:00Z"), "评价", "很慢"),
                new EventInput(3L, utcDate("2026-07-20T23:00:00Z"), "工单", "卡死"));
        Map<Long, List<Classification>> classificationsByEventId = Map.of(
                1L, List.of(new Classification(canonicalKey, 1.0, "rule")),
                2L, List.of(new Classification(canonicalKey, 1.0, "rule")),
                3L, List.of(new Classification(canonicalKey, 1.0, "rule")));
        Map<String, String> canonicalNames = Map.of(canonicalKey, "慢查询");

        service.write(projectionId, workspaceId, events, classificationsByEventId, canonicalNames);

        verify(bucketRepository).save(bucketArgMatcher(workspaceId, issueId, utcDate("2026-07-20T00:00:00Z"), 3, "{\"工单\":2,\"评价\":1}", projectionId));
    }

    /** 跨天分桶，verify 两次 save。 */
    @Test
    void splitsEventsAcrossDayBoundaries() {
        String canonicalKey = "timeout";
        stubCatalog(canonicalKey);
        List<EventInput> events = List.of(
                new EventInput(1L, utcDate("2026-07-20T10:00:00Z"), "工单", "超时"),
                new EventInput(2L, utcDate("2026-07-21T09:00:00Z"), "评价", "又超时"));
        Map<Long, List<Classification>> classificationsByEventId = Map.of(
                1L, List.of(new Classification(canonicalKey, 1.0, "rule")),
                2L, List.of(new Classification(canonicalKey, 1.0, "rule")));
        Map<String, String> canonicalNames = Map.of(canonicalKey, "超时");

        service.write(projectionId, workspaceId, events, classificationsByEventId, canonicalNames);

        verify(bucketRepository).save(bucketArgMatcher(workspaceId, issueId, utcDate("2026-07-20T00:00:00Z"), 1, "{\"工单\":1}", projectionId));
        verify(bucketRepository).save(bucketArgMatcher(workspaceId, issueId, utcDate("2026-07-21T00:00:00Z"), 1, "{\"评价\":1}", projectionId));
    }

    /** 已有桶追加，feedbackCount 从 5→6。 */
    @Test
    void upsertsWhenBucketExists() {
        String canonicalKey = "crash";
        stubCatalog(canonicalKey);
        OffsetDateTime bucketStart = utcDate("2026-07-20T00:00:00Z");
        IssueMetricBucket existing = IssueMetricBucket.of(workspaceId, issueId, bucketStart, 5, "{\"工单\":5}", projectionId);
        when(bucketRepository.findByWorkspaceIdAndIssueIdAndBucketStart(workspaceId, issueId, bucketStart))
                .thenReturn(Optional.of(existing));
        EventInput event = new EventInput(1L, utcDate("2026-07-20T10:00:00Z"), "评价", "崩溃了");
        Map<Long, List<Classification>> classificationsByEventId = Map.of(
                1L, List.of(new Classification(canonicalKey, 1.0, "rule")));
        Map<String, String> canonicalNames = Map.of(canonicalKey, "崩溃");

        service.write(projectionId, workspaceId, List.of(event), classificationsByEventId, canonicalNames);

        assertThat(existing.getFeedbackCount()).isEqualTo(6);
        assertThat(existing.getDimensionSummaryJson()).isEqualTo("{\"工单\":5,\"评价\":1}");
        verify(bucketRepository, never()).save(any(IssueMetricBucket.class));
    }

    /** unclassified 不产生任何 save。 */
    @Test
    void skipsUnclassifiedEvents() {
        EventInput event = new EventInput(1L, utcDate("2026-07-20T10:00:00Z"), "工单", "无主题");
        Map<Long, List<Classification>> classificationsByEventId = new HashMap<>();
        classificationsByEventId.put(1L, List.of());

        service.write(projectionId, workspaceId, List.of(event), classificationsByEventId, Map.of());

        verifyNoInteractions(catalogService);
        verify(bucketRepository, never()).save(any(IssueMetricBucket.class));
    }

    /** 空输入不抛异常不写。 */
    @Test
    void emptyInputReturnsWithoutWriting() {
        service.write(projectionId, workspaceId, List.of(), Map.of(), Map.of());

        verifyNoInteractions(catalogService);
        verify(bucketRepository, never()).save(any(IssueMetricBucket.class));
    }

    private static IssueMetricBucket bucketArgMatcher(Long workspaceId, Long issueId, OffsetDateTime bucketStart,
            int feedbackCount, String dimensionSummaryJson, Long projectionId) {
        return argThat(bucket ->
                bucket.getWorkspaceId().equals(workspaceId)
                        && bucket.getIssueId().equals(issueId)
                        && bucket.getBucketStart().equals(bucketStart)
                        && bucket.getFeedbackCount() == feedbackCount
                        && bucket.getDimensionSummaryJson().equals(dimensionSummaryJson)
                        && bucket.getWorkspaceProjectionId().equals(projectionId));
    }
}
