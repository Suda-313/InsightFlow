package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.common.exception.IssueNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.Alert;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.ExpressionMetricBucket;
import com.insightflow.entity.FeedbackIssueLink;
import com.insightflow.entity.FeedbackProjectionAnnotation;
import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.entity.Workspace;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.ExpressionMetricBucketRepository;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.repository.FeedbackIssueLinkRepository;
import com.insightflow.repository.FeedbackProjectionAnnotationRepository;
import com.insightflow.repository.FeedbackReviewCandidateRepository;
import com.insightflow.repository.IssueBaselineProfileRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import com.insightflow.service.analysis.ExpressionRulesLoader;
import com.insightflow.service.analysis.TopicPackDefaults;
import com.insightflow.service.analysis.TopicPackRegistry;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 看板服务单元测试；所有仓储和 {@link WorkspaceService} 均使用 mock，验证聚合逻辑。
 */
class DashboardServiceTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final DataCellRepository dataCellRepository = mock(DataCellRepository.class);
    private final IssueMetricBucketRepository issueMetricBucketRepository = mock(IssueMetricBucketRepository.class);
    private final AlertRepository alertRepository = mock(AlertRepository.class);
    private final IssueBaselineProfileRepository issueBaselineProfileRepository = mock(IssueBaselineProfileRepository.class);
    private final IssueCatalogRepository issueCatalogRepository = mock(IssueCatalogRepository.class);
    private final WorkspaceProjectionRepository workspaceProjectionRepository = mock(WorkspaceProjectionRepository.class);
    private final CellIssueRepository cellIssueRepository = mock(CellIssueRepository.class);
    private final FeedbackEventRepository feedbackEventRepository = mock(FeedbackEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FeedbackProjectionAnnotationRepository feedbackProjectionAnnotationRepository =
            mock(FeedbackProjectionAnnotationRepository.class);
    private final ExpressionMetricBucketRepository expressionMetricBucketRepository = mock(ExpressionMetricBucketRepository.class);
    private final FeedbackIssueLinkRepository feedbackIssueLinkRepository = mock(FeedbackIssueLinkRepository.class);
    private final FeedbackReviewCandidateRepository feedbackReviewCandidateRepository =
            mock(FeedbackReviewCandidateRepository.class);
    private final ExpressionRulesLoader expressionRulesLoader = expressionRulesLoader();
    private final TopicPackRegistry topicPackRegistry = topicPackRegistry();

    private final DashboardService dashboardService = new DashboardService(
            workspaceService,
            dataCellRepository,
            issueMetricBucketRepository,
            alertRepository,
            issueBaselineProfileRepository,
            issueCatalogRepository,
            workspaceProjectionRepository,
            cellIssueRepository,
            feedbackEventRepository,
            objectMapper,
            feedbackProjectionAnnotationRepository,
            expressionMetricBucketRepository,
            feedbackIssueLinkRepository,
            feedbackReviewCandidateRepository,
            expressionRulesLoader,
            topicPackRegistry);

    /** 测试专用：构造一个真实加载好平台规则的 ExpressionRulesLoader，避免为每条测试手写 5 类映射。 */
    private static ExpressionRulesLoader expressionRulesLoader() {
        ExpressionRulesLoader loader = new ExpressionRulesLoader();
        loader.load();
        return loader;
    }

    /** 测试专用：构造 TopicPackRegistry 并加载首包。 */
    private static TopicPackRegistry topicPackRegistry() {
        TopicPackRegistry registry = new TopicPackRegistry("game-chaoziran");
        registry.load();
        return registry;
    }

    @Test
    void getDashboardReturnsAggregatedData() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 1L);
        OffsetDateTime coverageEnd = OffsetDateTime.parse("2026-07-11T12:00:00Z");

        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(dataCellRepository.findByWorkspaceId(1L)).thenReturn(List.of(
                DataCell.of(1L, 10L, coverageEnd.minusDays(14), coverageEnd, "stream_end", 5, 100)));
        when(issueMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(any(), any()))
                .thenReturn(List.of(bucket(1L, 1L, 10, coverageEnd.minusDays(1))));
        IssueCatalog catalog = catalog(1L, "login_failure", "登录失败");
        when(issueCatalogRepository.findAllById(List.of(1L))).thenReturn(List.of(catalog));
        when(issueCatalogRepository.findByWorkspaceId(1L)).thenReturn(List.of(catalog));
        when(issueCatalogRepository.findById(1L)).thenReturn(Optional.of(catalog));
        when(alertRepository.findTop5ByWorkspaceIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(alert(1L, 1L, 7)));
        when(issueBaselineProfileRepository.findByWorkspaceId(1L)).thenReturn(List.of(
                profile(1L, 1L, "active")));
        when(workspaceProjectionRepository.findTopByWorkspaceIdOrderByCreatedAtDesc(1L))
                .thenReturn(Optional.of(projection(1L, 1L, "succeeded")));
        when(expressionMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(any(), any()))
                .thenReturn(List.of());
        when(feedbackProjectionAnnotationRepository.findByWorkspaceId(1L)).thenReturn(List.of());
        when(feedbackEventRepository.findByWorkspaceIdAndOccurredAtBetween(eq(1L), any(), any()))
                .thenReturn(List.of());
        when(feedbackReviewCandidateRepository.countByWorkspaceIdAndStatus(1L, "pending_review")).thenReturn(0L);

        DashboardService.DashboardResponse response = dashboardService.getDashboard(workspacePublicId, null, null);

        assertThat(response).isNotNull();
        assertThat(response.coverage().totalEvents()).isEqualTo(5);
        assertThat(response.analysisWindow()).isNotNull();
        assertThat(response.topIssues()).hasSize(1);
        assertThat(response.recentAlerts()).hasSize(1);
        assertThat(response.baselineStatus().active()).isEqualTo(1);
        assertThat(response.latestProjection().status()).isEqualTo("succeeded");
        assertThat(response.expressionSummary().topicPackId()).isEqualTo(topicPackRegistry.defaultPackId());
        assertThat(response.expressionSummary().distribution()).isNotEmpty();
    }

    /** L2 分布按分析窗口内标注计数（与钻取同源）；趋势桶按同一窗口过滤。 */
    @Test
    void getDashboardAggregatesExpressionDistributionFromAnnotations() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 5L);
        OffsetDateTime coverageEnd = OffsetDateTime.parse("2026-07-11T00:00:00Z");
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(dataCellRepository.findByWorkspaceId(5L)).thenReturn(List.of(
                DataCell.of(5L, 10L, coverageEnd.minusDays(14), coverageEnd, "stream_end", 100, 1000)));
        when(feedbackProjectionAnnotationRepository.findByWorkspaceId(5L)).thenReturn(List.of(
                FeedbackProjectionAnnotation.of(5L, 1L, 1L, "expr_suggestion", 1.0, false, "platform:expression:v1", "game-chaoziran", "v1"),
                FeedbackProjectionAnnotation.of(5L, 1L, 2L, "expr_suggestion", 1.0, false, "platform:expression:v1", "game-chaoziran", "v1"),
                FeedbackProjectionAnnotation.of(5L, 1L, 3L, "expr_praise", 1.0, false, "platform:expression:v1", "game-chaoziran", "v1")));
        com.insightflow.entity.FeedbackEvent event1 = feedbackEvent(1L, 5L, coverageEnd.minusDays(1));
        com.insightflow.entity.FeedbackEvent event2 = feedbackEvent(2L, 5L, coverageEnd.minusDays(2));
        com.insightflow.entity.FeedbackEvent event3 = feedbackEvent(3L, 5L, coverageEnd.minusDays(3));
        when(feedbackEventRepository.findByWorkspaceIdAndOccurredAtBetween(eq(5L), any(), any()))
                .thenReturn(List.of(event1, event2, event3));
        when(expressionMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(eq(5L), any()))
                .thenReturn(List.of(
                        ExpressionMetricBucket.of(5L, "expr_suggestion", coverageEnd.minusDays(2), 3, 1L)));
        when(feedbackReviewCandidateRepository.countByWorkspaceIdAndStatus(5L, "pending_review")).thenReturn(4L);

        DashboardService.DashboardResponse response = dashboardService.getDashboard(workspacePublicId, null, null);

        DashboardService.ExpressionSummary summary = response.expressionSummary();
        assertThat(summary.distribution()).extracting(DashboardService.ExpressionCount::key)
                .containsExactly("expr_suggestion", "expr_complaint", "expr_praise", "expr_neutral", "expr_other");
        assertThat(summary.distribution().stream()
                .filter(count -> count.key().equals("expr_suggestion")).findFirst().orElseThrow().feedbackCount())
                .isEqualTo(2);
        assertThat(summary.distribution().stream()
                .filter(count -> count.key().equals("expr_praise")).findFirst().orElseThrow().feedbackCount())
                .isEqualTo(1);
        assertThat(summary.trend()).isNotEmpty();
        assertThat(summary.reviewPendingCount()).isEqualTo(4);
    }

    /** L2→L1 钻取：按事件 id 交叉 feedback_issue_link，统计出该表达类目下的议题分布。 */
    @Test
    void getExpressionTopicsJoinsAnnotationsWithIssueLinks() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 6L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);

        FeedbackProjectionAnnotation annotation = FeedbackProjectionAnnotation.of(
                6L, 1L, 100L, "expr_suggestion", 1.0, false, "platform:expression:v1", "game-chaoziran", "v1");
        when(feedbackProjectionAnnotationRepository.findByWorkspaceIdAndPrimaryExpression(6L, "expr_suggestion"))
                .thenReturn(List.of(annotation));
        when(feedbackEventRepository.findByWorkspaceIdAndOccurredAtBetween(eq(6L), any(), any()))
                .thenReturn(List.of(feedbackEvent(100L, 6L, OffsetDateTime.now())));

        IssueCatalog catalog = catalog(30L, "topic_matchmaking", "匹配/组队");
        FeedbackIssueLink link = FeedbackIssueLink.active(6L, 100L, 30L, 1L, "rule", 1.0, null);
        when(feedbackIssueLinkRepository.findByWorkspaceIdAndFeedbackEventIdIn(eq(6L), eq(Set.of(100L))))
                .thenReturn(List.of(link));
        when(issueCatalogRepository.findAllById(Set.of(30L))).thenReturn(List.of(catalog));

        DashboardService.ExpressionTopicsResponse response =
                dashboardService.getExpressionTopics(workspacePublicId, "expr_suggestion", null, null);

        assertThat(response.expressionKey()).isEqualTo("expr_suggestion");
        assertThat(response.analysisWindow()).isNotNull();
        assertThat(response.topicPackId()).isEqualTo("game-chaoziran");
        assertThat(response.topics()).hasSize(1);
        assertThat(response.topics().get(0).canonicalKey()).isEqualTo("topic_matchmaking");
        assertThat(response.topics().get(0).feedbackCount()).isEqualTo(1);
    }

    /** 没有任何标注命中该表达类目时，返回空议题列表而不是抛异常。 */
    @Test
    void getExpressionTopicsReturnsEmptyWhenNoAnnotations() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 8L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(feedbackProjectionAnnotationRepository.findByWorkspaceIdAndPrimaryExpression(8L, "expr_praise"))
                .thenReturn(List.of());
        when(feedbackEventRepository.findByWorkspaceIdAndOccurredAtBetween(eq(8L), any(), any()))
                .thenReturn(List.of());

        DashboardService.ExpressionTopicsResponse response =
                dashboardService.getExpressionTopics(workspacePublicId, "expr_praise", null, null);

        assertThat(response.topics()).isEmpty();
    }

    /** 未知表达键必须直接拒绝，而不是静默返回空分布——避免前端拼错参数时误以为"零命中"。 */
    @Test
    void getExpressionTopicsRejectsUnknownExpressionKey() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 7L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> dashboardService.getExpressionTopics(workspacePublicId, "expr_unknown", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** L2×L1 交叉样本：只返回命中指定议题的事件文本，且最多 5 条。 */
    @Test
    void getExpressionTopicSamplesReturnsMatchingEventTexts() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 9L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);

        IssueCatalog catalog = catalog(40L, "topic_matchmaking", "匹配/组队");
        when(issueCatalogRepository.findByWorkspaceIdAndCanonicalKey(9L, "topic_matchmaking"))
                .thenReturn(Optional.of(catalog));

        FeedbackProjectionAnnotation annotation = FeedbackProjectionAnnotation.of(
                9L, 1L, 200L, "expr_suggestion", 1.0, false, "platform:expression:v1", "game-chaoziran", "v1");
        when(feedbackProjectionAnnotationRepository.findByWorkspaceIdAndPrimaryExpression(9L, "expr_suggestion"))
                .thenReturn(List.of(annotation));
        when(feedbackEventRepository.findByWorkspaceIdAndOccurredAtBetween(eq(9L), any(), any()))
                .thenReturn(List.of(feedbackEvent(200L, 9L, OffsetDateTime.now())));

        FeedbackIssueLink link = FeedbackIssueLink.active(9L, 200L, 40L, 1L, "rule", 1.0, null);
        when(feedbackIssueLinkRepository.findByWorkspaceIdAndFeedbackEventIdIn(eq(9L), eq(Set.of(200L))))
                .thenReturn(List.of(link));

        com.insightflow.entity.FeedbackEvent event = feedbackEvent(200L, 9L, OffsetDateTime.now());
        setField(event, "sanitizedText", "希望优化匹配速度");
        setField(event, "sourceKind", "app_store");
        when(feedbackEventRepository.findByWorkspaceIdAndIdIn(eq(9L), eq(List.of(200L))))
                .thenReturn(List.of(event));

        List<DashboardService.FeedbackSample> samples =
                dashboardService.getExpressionTopicSamples(workspacePublicId, "expr_suggestion", "topic_matchmaking", null, null);

        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).text()).isEqualTo("希望优化匹配速度");
        assertThat(samples.get(0).sourceKind()).isEqualTo("app_store");
    }

    /** alert_eligible 子集：只统计 Pack 内标记 eligible 的议题，排除 topic_general 等。 */
    @Test
    void getAlertEligibleOverviewAggregatesEligibleTopicsOnly() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 10L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(dataCellRepository.findByWorkspaceId(10L)).thenReturn(List.of(
                DataCell.of(10L, 1L, OffsetDateTime.now().minusDays(14), OffsetDateTime.now(), "stream_end", 10, 100)));

        IssueCatalog stability = catalog(50L, "topic_stability", "稳定性/bug");
        IssueCatalog network = catalog(51L, "topic_network", "网络/登录");
        IssueCatalog general = catalog(52L, TopicPackDefaults.TOPIC_GENERAL_KEY, "综合/未指向");
        when(issueCatalogRepository.findByWorkspaceId(10L)).thenReturn(List.of(stability, network, general));

        com.insightflow.entity.FeedbackEvent event = feedbackEvent(300L, 10L, OffsetDateTime.now());
        when(feedbackEventRepository.findByWorkspaceIdAndOccurredAtBetween(eq(10L), any(), any()))
                .thenReturn(List.of(event));
        FeedbackIssueLink stabilityLink = FeedbackIssueLink.active(10L, 300L, 50L, 1L, "rule", 1.0, null);
        FeedbackIssueLink generalLink = FeedbackIssueLink.active(10L, 300L, 52L, 1L, "rule", 1.0, null);
        when(feedbackIssueLinkRepository.findByWorkspaceIdAndFeedbackEventIdIn(eq(10L), eq(Set.of(300L))))
                .thenReturn(List.of(stabilityLink, generalLink));

        OffsetDateTime bucketDay = OffsetDateTime.now().minusDays(1);
        when(issueMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(eq(10L), any()))
                .thenReturn(List.of(
                        bucketForIssue(50L, 10L, 3, bucketDay.minusDays(1)),
                        bucketForIssue(50L, 10L, 5, bucketDay),
                        bucketForIssue(51L, 10L, 2, bucketDay)));

        when(issueCatalogRepository.findById(50L)).thenReturn(Optional.of(stability));
        when(alertRepository.findTop5ByWorkspaceIdOrderByCreatedAtDesc(10L))
                .thenReturn(List.of(alert(10L, 50L, 8)));

        DashboardService.AlertEligibleOverviewResponse response =
                dashboardService.getAlertEligibleOverview(workspacePublicId, null, null);

        assertThat(response.eligibleTopicCount()).isEqualTo(5);
        assertThat(response.totalFeedbackCount()).isEqualTo(1);
        assertThat(response.topics()).extracting(DashboardService.AlertEligibleTopicSummary::canonicalKey)
                .contains("topic_stability", "topic_network");
        assertThat(response.topics().stream()
                .filter(topic -> topic.canonicalKey().equals("topic_stability")).findFirst().orElseThrow()
                .feedbackCount()).isEqualTo(1);
        assertThat(response.topics().stream()
                .filter(topic -> topic.canonicalKey().equals("topic_stability")).findFirst().orElseThrow()
                .trendDirection()).isEqualTo("up");
        assertThat(response.trend()).isNotEmpty();
        assertThat(response.recentAlerts()).hasSize(1);
        assertThat(response.topicPackId()).isEqualTo("game-chaoziran");
    }

    /** 窗口内无 eligible 议题 link 时仍返回 Pack 目录骨架，计数为 0。 */
    @Test
    void getAlertEligibleOverviewReturnsEmptyCountsWhenNoLinks() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 11L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(dataCellRepository.findByWorkspaceId(11L)).thenReturn(List.of(
                DataCell.of(11L, 1L, OffsetDateTime.now().minusDays(14), OffsetDateTime.now(), "stream_end", 5, 50)));
        when(issueCatalogRepository.findByWorkspaceId(11L)).thenReturn(List.of());
        when(feedbackEventRepository.findByWorkspaceIdAndOccurredAtBetween(eq(11L), any(), any()))
                .thenReturn(List.of());
        when(issueMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(eq(11L), any()))
                .thenReturn(List.of());
        when(alertRepository.findTop5ByWorkspaceIdOrderByCreatedAtDesc(11L)).thenReturn(List.of());

        DashboardService.AlertEligibleOverviewResponse response =
                dashboardService.getAlertEligibleOverview(workspacePublicId, null, null);

        assertThat(response.totalFeedbackCount()).isZero();
        assertThat(response.topics()).hasSize(5);
        assertThat(response.topics()).allMatch(topic -> topic.feedbackCount() == 0);
    }

    @Test
    void getIssuesReturnsSortedIssueSummaries() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 2L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);

        IssueCatalog first = catalog(10L, "login_failure", "登录失败");
        IssueCatalog second = catalog(11L, "checkout_error", "结账失败");
        when(issueCatalogRepository.findByWorkspaceId(2L)).thenReturn(List.of(first, second));
        when(issueMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(eq(2L), any()))
                .thenReturn(List.of(
                        bucket(10L, 2L, 5),
                        bucket(11L, 2L, 10),
                        bucket(10L, 2L, 3)));

        List<DashboardService.IssueSummary> issues = dashboardService.getIssues(workspacePublicId, null, null);

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0).canonicalKey()).isEqualTo("checkout_error");
        assertThat(issues.get(0).feedbackCount()).isEqualTo(10);
        assertThat(issues.get(1).feedbackCount()).isEqualTo(8);
    }

    @Test
    void getIssueDetailReturnsIssueInformation() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 3L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);

        IssueCatalog catalog = catalog(20L, "login_failure", "登录失败");
        when(issueCatalogRepository.findByWorkspaceIdAndCanonicalKey(3L, "login_failure"))
                .thenReturn(Optional.of(catalog));
        OffsetDateTime now = OffsetDateTime.now();
        when(issueMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(any(), any()))
                .thenReturn(List.of(
                        bucketForIssue(20L, 3L, 4, now),
                        bucketForIssue(20L, 3L, 6, now.minusDays(1))));
        when(alertRepository.findByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(3L, 20L))
                .thenReturn(List.of(alert(3L, 20L, 7)));
        when(issueBaselineProfileRepository.findByWorkspaceIdAndIssueId(3L, 20L))
                .thenReturn(Optional.of(profile(3L, 20L, "active")));
        when(cellIssueRepository.findByIssueId(20L)).thenReturn(List.of());
        when(issueCatalogRepository.findById(20L)).thenReturn(Optional.of(catalog));
        when(feedbackEventRepository.findByWorkspaceIdAndOccurredAtBetween(eq(3L), any(), any()))
                .thenReturn(List.of());
        when(feedbackIssueLinkRepository.findByWorkspaceIdAndFeedbackEventIdIn(eq(3L), eq(Set.of())))
                .thenReturn(List.of());

        DashboardService.IssueDetailResponse detail =
                dashboardService.getIssueDetail(workspacePublicId, "login_failure", null, null);

        assertThat(detail.canonicalKey()).isEqualTo("login_failure");
        assertThat(detail.analysisWindow()).isNotNull();
        assertThat(detail.recentTrend()).hasSize(2);
        assertThat(detail.alerts()).hasSize(1);
        assertThat(detail.baseline().status()).isEqualTo("active");
    }

    @Test
    void getIssueDetailThrowsWhenIssueMissing() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 4L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(issueCatalogRepository.findByWorkspaceIdAndCanonicalKey(4L, "missing"))
                .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> dashboardService.getIssueDetail(workspacePublicId, "missing", null, null))
                .isInstanceOf(IssueNotFoundException.class);
    }

    private Workspace workspaceWithId(UUID publicId, long id) throws Exception {
        Workspace workspace = new Workspace("test", 1L);
        setField(workspace, "publicId", publicId);
        setField(workspace, "id", id);
        return workspace;
    }

    private IssueCatalog catalog(long id, String key, String name) throws Exception {
        IssueCatalog catalog = IssueCatalog.create(1L, key, name);
        setField(catalog, "id", id);
        return catalog;
    }

    private IssueMetricBucket bucket(long issueId, long workspaceId, int count) {
        return IssueMetricBucket.of(workspaceId, issueId, OffsetDateTime.now(), count, "{}", 1L);
    }

    private IssueMetricBucket bucket(long issueId, long workspaceId, int count, OffsetDateTime bucketStart) {
        return IssueMetricBucket.of(workspaceId, issueId, bucketStart, count, "{}", 1L);
    }

    private IssueMetricBucket bucketForIssue(long issueId, long workspaceId, int count, OffsetDateTime bucketStart) {
        return IssueMetricBucket.of(workspaceId, issueId, bucketStart, count, "{}", 1L);
    }

    private Alert alert(long workspaceId, long issueId, int currentCount) {
        return Alert.active(workspaceId, issueId, 1L, OffsetDateTime.now(), currentCount,
                1.0, 0.5, 2.0, 5, "[]");
    }

    private IssueBaselineProfile profile(long workspaceId, long issueId, String status) throws Exception {
        IssueBaselineProfile profile = IssueBaselineProfile.create(workspaceId, issueId, OffsetDateTime.now(), 5, 7);
        setField(profile, "status", status);
        return profile;
    }

    private WorkspaceProjection projection(long workspaceId, long asyncTaskId, String status) throws Exception {
        WorkspaceProjection projection = WorkspaceProjection.queued(workspaceId, asyncTaskId, "v1");
        projection.markSucceeded(OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
        setField(projection, "status", status);
        return projection;
    }

    private com.insightflow.entity.FeedbackEvent feedbackEvent(long id, long workspaceId, OffsetDateTime occurredAt)
            throws Exception {
        com.insightflow.entity.FeedbackEvent event = com.insightflow.entity.FeedbackEvent.active(
                workspaceId, 1L, "hash", occurredAt, "test", "sample", "sample", "{}", "content", 1L);
        setField(event, "id", id);
        return event;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
