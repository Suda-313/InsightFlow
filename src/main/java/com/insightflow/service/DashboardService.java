package com.insightflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.common.exception.IssueNotFoundException;
import com.insightflow.entity.Alert;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.ExpressionMetricBucket;
import com.insightflow.entity.FeedbackEvent;
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
import com.insightflow.service.analysis.AnalysisWindowResolver;
import com.insightflow.service.analysis.AnalysisWindowResolver.AnalysisWindow;
import com.insightflow.service.analysis.ExpressionDefaults;
import com.insightflow.service.analysis.ExpressionRulesLoader;
import com.insightflow.service.analysis.TopicPackLoader;
import com.insightflow.service.analysis.TopicPackRegistry;
import com.insightflow.service.analysis.TopicPackTopic;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 看板数据查询用例层，负责聚合多个仓储数据并投影为受控的 API 响应。
 *
 * <p>所有查询都先通过 {@link WorkspaceService} 取得工作区，保证一级租户隔离；
 * 聚合与排序在应用层完成，避免仓储承担过多展示逻辑。</p>
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final int TOP_ISSUES_LIMIT = 5;
    private static final int RECENT_ALERTS_LIMIT = 5;

    private final WorkspaceService workspaceService;
    private final DataCellRepository dataCellRepository;
    private final IssueMetricBucketRepository issueMetricBucketRepository;
    private final AlertRepository alertRepository;
    private final IssueBaselineProfileRepository issueBaselineProfileRepository;
    private final IssueCatalogRepository issueCatalogRepository;
    private final WorkspaceProjectionRepository workspaceProjectionRepository;
    private final CellIssueRepository cellIssueRepository;
    private final FeedbackEventRepository feedbackEventRepository;
    private final ObjectMapper objectMapper;
    // 以下为 L2 表达层 / Topic Pack 依赖：聚合首屏 L2 分布与 L2→L1 钻取，独立于既有 L1 聚合链路。
    private final FeedbackProjectionAnnotationRepository feedbackProjectionAnnotationRepository;
    private final ExpressionMetricBucketRepository expressionMetricBucketRepository;
    private final FeedbackIssueLinkRepository feedbackIssueLinkRepository;
    private final FeedbackReviewCandidateRepository feedbackReviewCandidateRepository;
    private final ExpressionRulesLoader expressionRulesLoader;
    private final TopicPackRegistry topicPackRegistry;

    /**
     * 通过构造器注入所有依赖，便于单元测试替换为 mock。
     */
    public DashboardService(
            WorkspaceService workspaceService,
            DataCellRepository dataCellRepository,
            IssueMetricBucketRepository issueMetricBucketRepository,
            AlertRepository alertRepository,
            IssueBaselineProfileRepository issueBaselineProfileRepository,
            IssueCatalogRepository issueCatalogRepository,
            WorkspaceProjectionRepository workspaceProjectionRepository,
            CellIssueRepository cellIssueRepository,
            FeedbackEventRepository feedbackEventRepository,
            ObjectMapper objectMapper,
            FeedbackProjectionAnnotationRepository feedbackProjectionAnnotationRepository,
            ExpressionMetricBucketRepository expressionMetricBucketRepository,
            FeedbackIssueLinkRepository feedbackIssueLinkRepository,
            FeedbackReviewCandidateRepository feedbackReviewCandidateRepository,
            ExpressionRulesLoader expressionRulesLoader,
            TopicPackRegistry topicPackRegistry) {
        this.workspaceService = workspaceService;
        this.dataCellRepository = dataCellRepository;
        this.issueMetricBucketRepository = issueMetricBucketRepository;
        this.alertRepository = alertRepository;
        this.issueBaselineProfileRepository = issueBaselineProfileRepository;
        this.issueCatalogRepository = issueCatalogRepository;
        this.workspaceProjectionRepository = workspaceProjectionRepository;
        this.cellIssueRepository = cellIssueRepository;
        this.feedbackEventRepository = feedbackEventRepository;
        this.objectMapper = objectMapper;
        this.feedbackProjectionAnnotationRepository = feedbackProjectionAnnotationRepository;
        this.expressionMetricBucketRepository = expressionMetricBucketRepository;
        this.feedbackIssueLinkRepository = feedbackIssueLinkRepository;
        this.feedbackReviewCandidateRepository = feedbackReviewCandidateRepository;
        this.expressionRulesLoader = expressionRulesLoader;
        this.topicPackRegistry = topicPackRegistry;
    }

    /**
     * 聚合看板首页所需数据：数据覆盖、Top 5 问题、最近告警、基线状态、最新投影、L2 表达分布。
     *
     * <p>按 spec §6.4 修订：首屏以 L2（表达/意图）分布与趋势为主视图，L1 议题 Top5 仍保留
     * 作为既有二级信息，不重复展示"已纳入可行动主题 X%"等已废弃指标。</p>
     */
    public DashboardResponse getDashboard(UUID workspacePublicId, LocalDate from, LocalDate to) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();

        DataCoverage coverage = buildDataCoverage(workspaceId);
        AnalysisWindow window = AnalysisWindowResolver.resolve(coverage, from, to);
        return buildDashboard(workspace, coverage, window);
    }

    /**
     * 报告 Worker 使用精确的带时区时间范围，避免先降级为 LocalDate 后把边界日的整天数据误并入报告。
     * 调用方已负责校验左闭右开区间；看板内部仍沿用现有的聚合查询逻辑。
     */
    public DashboardResponse getDashboardForTimeRange(UUID workspacePublicId, OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("看板时间范围必须为有效的左闭右开区间");
        }
        Workspace workspace = workspaceService.get(workspacePublicId);
        return buildDashboard(workspace, buildDataCoverage(workspace.getId()), new AnalysisWindow(start, end.minusNanos(1)));
    }

    /** 集中组装两个时间范围入口共享的脱敏看板投影，防止报告与页面字段漂移。 */
    private DashboardResponse buildDashboard(Workspace workspace, DataCoverage coverage, AnalysisWindow window) {
        Long workspaceId = workspace.getId();
        List<IssueSummary> topIssues = buildTopIssues(workspaceId, window);
        List<AlertSummary> recentAlerts = buildRecentAlerts(workspaceId);
        BaselineStatus baselineStatus = buildBaselineStatus(workspaceId);
        ProjectionSummary latestProjection = buildLatestProjection(workspaceId);
        ExpressionSummary expressionSummary = buildExpressionSummary(workspace, coverage, window);

        return new DashboardResponse(
                coverage,
                toWindowInfo(window),
                topIssues,
                recentAlerts,
                baselineStatus,
                latestProjection,
                expressionSummary);
    }

    /**
     * L2→L1 钻取：给定一个 L2 表达类目，返回该类目下反馈在 Workspace 当前 Topic Pack 内
     * 的 L1 议题分布（含 topic_general）。
     *
     * <p>实现按 spec §6.3 采用"标注 ⋈ link"实时 JOIN，不为交叉分布单独建表——量级是
     * 单 Workspace 近期反馈数，实时聚合足够，避免为尚未验证的访问量预先做物化。</p>
     */
    public ExpressionTopicsResponse getExpressionTopics(
            UUID workspacePublicId, String expressionKey, LocalDate from, LocalDate to) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();
        validateExpressionKey(expressionKey);

        DataCoverage coverage = buildDataCoverage(workspaceId);
        AnalysisWindow window = AnalysisWindowResolver.resolve(coverage, from, to);
        Set<Long> eventIdsInWindow = eventIdsInWindow(workspaceId, window);

        List<FeedbackProjectionAnnotation> annotations =
                feedbackProjectionAnnotationRepository.findByWorkspaceIdAndPrimaryExpression(workspaceId, expressionKey)
                        .stream()
                        .filter(annotation -> eventIdsInWindow.contains(annotation.getFeedbackEventId()))
                        .toList();
        if (annotations.isEmpty()) {
            TopicPackLoader pack = topicPackRegistry.resolveForWorkspace(workspace);
            return new ExpressionTopicsResponse(
                    expressionKey, pack.packId(), pack.packVersion(), List.of(), toWindowInfo(window));
        }

        Set<Long> eventIds = annotations.stream().map(FeedbackProjectionAnnotation::getFeedbackEventId).collect(Collectors.toSet());
        List<FeedbackIssueLink> links = feedbackIssueLinkRepository.findByWorkspaceIdAndFeedbackEventIdIn(workspaceId, eventIds);

        Map<Long, Integer> countByIssue = links.stream()
                .collect(Collectors.groupingBy(FeedbackIssueLink::getIssueId, Collectors.summingInt(link -> 1)));
        Map<Long, IssueCatalog> catalogById = issueCatalogRepository.findAllById(countByIssue.keySet()).stream()
                .collect(Collectors.toMap(IssueCatalog::getId, catalog -> catalog));

        List<TopicCount> topics = countByIssue.entrySet().stream()
                .map(entry -> {
                    IssueCatalog catalog = catalogById.get(entry.getKey());
                    return new TopicCount(catalog.getPublicId(), catalog.getCanonicalKey(), catalog.getCanonicalName(), entry.getValue());
                })
                .sorted(Comparator.comparingInt(TopicCount::feedbackCount).reversed())
                .toList();

        TopicPackLoader pack = topicPackRegistry.resolveForWorkspace(workspace);
        return new ExpressionTopicsResponse(expressionKey, pack.packId(), pack.packVersion(), topics, toWindowInfo(window));
    }

    /**
     * alert_eligible 子集概览：仅统计当前 Workspace Topic Pack 内标记为可行动告警的 L1 议题。
     *
     * <p>按 spec §7.2 副屏设计，与首屏 L2 主视图并列展示；计数与趋势均按统一分析窗口过滤，
     * 只读展示不参与告警状态变更。eligible 资格来自 Pack 目录 {@code alert_eligible} 字段，
     * 与 {@code topic_general}（固定 false）及非 eligible 议题隔离。</p>
     */
    public AlertEligibleOverviewResponse getAlertEligibleOverview(
            UUID workspacePublicId, LocalDate from, LocalDate to) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();

        DataCoverage coverage = buildDataCoverage(workspaceId);
        AnalysisWindow window = AnalysisWindowResolver.resolve(coverage, from, to);
        TopicPackLoader pack = topicPackRegistry.resolveForWorkspace(workspace);

        List<TopicPackTopic> eligiblePackTopics = pack.topics().stream()
                .filter(TopicPackTopic::alertEligible)
                .toList();
        Map<String, IssueCatalog> catalogByKey = issueCatalogRepository.findByWorkspaceId(workspaceId).stream()
                .collect(Collectors.toMap(IssueCatalog::getCanonicalKey, catalog -> catalog, (a, b) -> a));
        Set<Long> eligibleIssueIds = eligiblePackTopics.stream()
                .map(TopicPackTopic::canonicalKey)
                .map(catalogByKey::get)
                .filter(catalog -> catalog != null)
                .map(IssueCatalog::getId)
                .collect(Collectors.toSet());

        final Map<Long, Integer> countByIssue;
        if (!eligibleIssueIds.isEmpty()) {
            Set<Long> eventIdsInWindow = eventIdsInWindow(workspaceId, window);
            if (!eventIdsInWindow.isEmpty()) {
                countByIssue = feedbackIssueLinkRepository
                        .findByWorkspaceIdAndFeedbackEventIdIn(workspaceId, eventIdsInWindow)
                        .stream()
                        .filter(link -> eligibleIssueIds.contains(link.getIssueId()))
                        .collect(Collectors.groupingBy(
                                FeedbackIssueLink::getIssueId,
                                Collectors.summingInt(link -> 1)));
            } else {
                countByIssue = Map.of();
            }
        } else {
            countByIssue = Map.of();
        }

        List<IssueMetricBucket> eligibleBuckets = eligibleIssueIds.isEmpty()
                ? List.of()
                : issueMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, window.start())
                        .stream()
                        .filter(bucket -> !bucket.getBucketStart().isAfter(window.end()))
                        .filter(bucket -> eligibleIssueIds.contains(bucket.getIssueId()))
                        .toList();

        Map<Long, List<IssueMetricBucket>> bucketsByIssue = eligibleBuckets.stream()
                .collect(Collectors.groupingBy(IssueMetricBucket::getIssueId));

        List<AlertEligibleTrendPoint> trend = eligibleBuckets.stream()
                .collect(Collectors.groupingBy(IssueMetricBucket::getBucketStart))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AlertEligibleTrendPoint(
                        entry.getKey(),
                        entry.getValue().stream().mapToInt(IssueMetricBucket::getFeedbackCount).sum()))
                .toList();

        int totalFeedbackCount = countByIssue.values().stream().mapToInt(Integer::intValue).sum();
        List<AlertEligibleTopicSummary> topics = eligiblePackTopics.stream()
                .map(packTopic -> {
                    IssueCatalog catalog = catalogByKey.get(packTopic.canonicalKey());
                    int count = catalog == null ? 0 : countByIssue.getOrDefault(catalog.getId(), 0);
                    String trendDirection = catalog == null
                            ? "flat"
                            : computeTopicTrendDirection(bucketsByIssue.getOrDefault(catalog.getId(), List.of()));
                    UUID topicPublicId = catalog == null ? null : catalog.getPublicId();
                    return new AlertEligibleTopicSummary(
                            topicPublicId, packTopic.canonicalKey(), packTopic.name(), count, trendDirection);
                })
                .sorted(Comparator.comparingInt(AlertEligibleTopicSummary::feedbackCount).reversed())
                .toList();

        List<AlertSummary> recentAlerts = eligibleIssueIds.isEmpty()
                ? List.of()
                : alertRepository.findTop5ByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
                        .stream()
                        .filter(alert -> eligibleIssueIds.contains(alert.getIssueId()))
                        .map(this::toAlertSummary)
                        .toList();

        return new AlertEligibleOverviewResponse(
                toWindowInfo(window),
                totalFeedbackCount,
                eligiblePackTopics.size(),
                topics,
                trend,
                recentAlerts,
                pack.packId(),
                pack.packVersion());
    }

    /** 比较最近两天桶计数，供副屏展示简易趋势箭头（up/flat/down）。 */
    private String computeTopicTrendDirection(List<IssueMetricBucket> buckets) {
        if (buckets.size() < 2) {
            return "flat";
        }
        List<IssueMetricBucket> sorted = buckets.stream()
                .sorted(Comparator.comparing(IssueMetricBucket::getBucketStart))
                .toList();
        int last = sorted.get(sorted.size() - 1).getFeedbackCount();
        int previous = sorted.get(sorted.size() - 2).getFeedbackCount();
        if (last > previous) {
            return "up";
        }
        if (last < previous) {
            return "down";
        }
        return "flat";
    }

    /**
     * L2×L1 交叉样本：给定表达类目与 Pack 内议题键，返回最多 5 条脱敏样本文本供人工核验分类质量。
     */
    public List<FeedbackSample> getExpressionTopicSamples(
            UUID workspacePublicId, String expressionKey, String topicKey, LocalDate from, LocalDate to) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();
        validateExpressionKey(expressionKey);

        IssueCatalog catalog = issueCatalogRepository.findByWorkspaceIdAndCanonicalKey(workspaceId, topicKey)
                .orElseThrow(() -> new IssueNotFoundException(topicKey));

        DataCoverage coverage = buildDataCoverage(workspaceId);
        AnalysisWindow window = AnalysisWindowResolver.resolve(coverage, from, to);
        Set<Long> eventIdsInWindow = eventIdsInWindow(workspaceId, window);

        List<FeedbackProjectionAnnotation> annotations =
                feedbackProjectionAnnotationRepository.findByWorkspaceIdAndPrimaryExpression(workspaceId, expressionKey)
                        .stream()
                        .filter(annotation -> eventIdsInWindow.contains(annotation.getFeedbackEventId()))
                        .toList();
        Set<Long> expressionEventIds = annotations.stream()
                .map(FeedbackProjectionAnnotation::getFeedbackEventId)
                .collect(Collectors.toSet());
        if (expressionEventIds.isEmpty()) {
            return List.of();
        }

        List<FeedbackIssueLink> links = feedbackIssueLinkRepository
                .findByWorkspaceIdAndFeedbackEventIdIn(workspaceId, expressionEventIds);

        List<Long> sampleEventIds = links.stream()
                .filter(link -> catalog.getId().equals(link.getIssueId()))
                .map(FeedbackIssueLink::getFeedbackEventId)
                .distinct()
                .limit(5)
                .toList();

        Map<Long, FeedbackEvent> eventsById = feedbackEventRepository.findByWorkspaceIdAndIdIn(workspaceId, sampleEventIds)
                .stream()
                .collect(Collectors.toMap(FeedbackEvent::getId, event -> event));

        return sampleEventIds.stream()
                .map(eventsById::get)
                .filter(event -> event != null)
                .map(this::toFeedbackSample)
                .toList();
    }

    /** 校验 exprKey 属于平台固定 5 类之一；未知键视为非法请求而非静默返回空结果。 */
    private void validateExpressionKey(String expressionKey) {
        if (!expressionDisplayNames().containsKey(expressionKey)) {
            throw new IllegalArgumentException("Unknown expression key: " + expressionKey);
        }
    }

    /**
     * 汇总 L2 分布与趋势（均按统一分析窗口过滤）、L1 待复核次要 KPI、Pack 信息。
     *
     * <p>分布与钻取均按 {@code feedback_event.occurred_at} 过滤标注行，与 L2→L1 钻取同源。
     * 趋势用 {@code expression_metric_bucket}，桶起点落在同一分析窗口内。</p>
     */
    private ExpressionSummary buildExpressionSummary(Workspace workspace, DataCoverage coverage, AnalysisWindow window) {
        Long workspaceId = workspace.getId();
        Set<Long> eventIdsInWindow = eventIdsInWindow(workspaceId, window);

        Map<String, Long> annotationCounts = feedbackProjectionAnnotationRepository.findByWorkspaceId(workspaceId)
                .stream()
                .filter(annotation -> eventIdsInWindow.contains(annotation.getFeedbackEventId()))
                .collect(Collectors.groupingBy(
                        FeedbackProjectionAnnotation::getPrimaryExpression,
                        Collectors.counting()));

        List<ExpressionMetricBucket> buckets = expressionMetricBucketRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, window.start())
                .stream()
                .filter(bucket -> !bucket.getBucketStart().isAfter(window.end()))
                .toList();

        Map<String, String> displayNames = expressionDisplayNames();
        List<ExpressionCount> distribution = displayNames.entrySet().stream()
                .map(entry -> new ExpressionCount(
                        entry.getKey(),
                        entry.getValue(),
                        annotationCounts.getOrDefault(entry.getKey(), 0L).intValue()))
                .toList();

        List<ExpressionTrendPoint> trend = buckets.stream()
                .collect(Collectors.groupingBy(ExpressionMetricBucket::getBucketStart))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ExpressionTrendPoint(entry.getKey(), entry.getValue().stream()
                        .collect(Collectors.toMap(
                                ExpressionMetricBucket::getPrimaryExpression,
                                ExpressionMetricBucket::getFeedbackCount))))
                .toList();

        long reviewPendingCount = feedbackReviewCandidateRepository.countByWorkspaceIdAndStatus(workspaceId, "pending_review");
        TopicPackLoader pack = topicPackRegistry.resolveForWorkspace(workspace);

        return new ExpressionSummary(distribution, trend, (int) reviewPendingCount,
                pack.packId(), pack.packVersion());
    }

    /** 分析窗口内的事件 id 集合；统一 L2 分布、钻取与 L1 计数的 occurred_at 过滤口径。 */
    private Set<Long> eventIdsInWindow(Long workspaceId, AnalysisWindow window) {
        return feedbackEventRepository.findByWorkspaceIdAndOccurredAtBetween(workspaceId, window.start(), window.end())
                .stream()
                .map(FeedbackEvent::getId)
                .collect(Collectors.toSet());
    }

    private WindowInfo toWindowInfo(AnalysisWindow window) {
        return new WindowInfo(window.start(), window.end());
    }

    private FeedbackSample toFeedbackSample(FeedbackEvent event) {
        return new FeedbackSample(event.getSanitizedText(), event.getOccurredAt(), event.getSourceKind());
    }

    /** 按规则文件顺序构建 L2 键→展示名映射，并追加固定兜底类目 expr_other。 */
    private Map<String, String> expressionDisplayNames() {
        Map<String, String> names = new LinkedHashMap<>();
        expressionRulesLoader.rules().forEach(rule -> names.put(rule.canonicalKey(), rule.name()));
        names.put(ExpressionDefaults.EXPR_OTHER_KEY, ExpressionDefaults.EXPR_OTHER_NAME);
        return names;
    }

    /**
     * 返回工作区下所有 issue 的汇总列表，按总反馈数降序排列。
     */
    public List<IssueSummary> getIssues(UUID workspacePublicId, LocalDate from, LocalDate to) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();

        DataCoverage coverage = buildDataCoverage(workspaceId);
        AnalysisWindow window = AnalysisWindowResolver.resolve(coverage, from, to);

        Map<Long, List<IssueMetricBucket>> bucketsByIssue = issueMetricBucketRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, window.start())
                .stream()
                .filter(bucket -> !bucket.getBucketStart().isAfter(window.end()))
                .collect(Collectors.groupingBy(IssueMetricBucket::getIssueId));

        List<IssueCatalog> catalogs = issueCatalogRepository.findByWorkspaceId(workspaceId);

        return catalogs.stream()
                .map(catalog -> toIssueSummary(catalog, bucketsByIssue.getOrDefault(catalog.getId(), List.of())))
                .sorted(Comparator.comparingInt(IssueSummary::feedbackCount).reversed())
                .toList();
    }

    /**
     * 返回单个 issue 的详细数据：主题信息、最近 7 天趋势、告警历史、基线状态。
     */
    public IssueDetailResponse getIssueDetail(
            UUID workspacePublicId, String canonicalKey, LocalDate from, LocalDate to) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();

        IssueCatalog catalog = issueCatalogRepository.findByWorkspaceIdAndCanonicalKey(workspaceId, canonicalKey)
                .orElseThrow(() -> new IssueNotFoundException(canonicalKey));

        DataCoverage coverage = buildDataCoverage(workspaceId);
        AnalysisWindow window = AnalysisWindowResolver.resolve(coverage, from, to);

        List<IssueMetricBucket> recentBuckets = issueMetricBucketRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, window.start())
                .stream()
                .filter(bucket -> bucket.getIssueId().equals(catalog.getId()))
                .filter(bucket -> !bucket.getBucketStart().isAfter(window.end()))
                .sorted(Comparator.comparing(IssueMetricBucket::getBucketStart))
                .toList();

        List<AlertSummary> alerts = alertRepository
                .findByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(workspaceId, catalog.getId())
                .stream()
                .map(this::toAlertSummary)
                .toList();

        BaselineInfo baseline = issueBaselineProfileRepository.findByWorkspaceIdAndIssueId(workspaceId, catalog.getId())
                .map(this::toBaselineInfo)
                .orElse(null);

        Set<Long> eventIdsInWindow = eventIdsInWindow(workspaceId, window);
        List<FeedbackIssueLink> links = feedbackIssueLinkRepository
                .findByWorkspaceIdAndFeedbackEventIdIn(workspaceId, eventIdsInWindow);
        List<Long> sampleEventIds = links.stream()
                .filter(link -> catalog.getId().equals(link.getIssueId()))
                .map(FeedbackIssueLink::getFeedbackEventId)
                .distinct()
                .limit(5)
                .toList();
        Map<Long, FeedbackEvent> eventsById = feedbackEventRepository.findByWorkspaceIdAndIdIn(workspaceId, sampleEventIds)
                .stream()
                .collect(Collectors.toMap(FeedbackEvent::getId, event -> event));
        List<FeedbackSample> samples = sampleEventIds.stream()
                .map(eventsById::get)
                .filter(event -> event != null)
                .map(this::toFeedbackSample)
                .toList();

        return new IssueDetailResponse(
                catalog.getPublicId(),
                catalog.getCanonicalKey(),
                catalog.getCanonicalName(),
                catalog.getStatus(),
                recentBuckets.stream()
                        .map(bucket -> new TrendPoint(bucket.getBucketStart(), bucket.getFeedbackCount()))
                        .toList(),
                alerts,
                baseline,
                samples,
                toWindowInfo(window));
    }

    private DataCoverage buildDataCoverage(Long workspaceId) {
        List<DataCell> cells = dataCellRepository.findByWorkspaceId(workspaceId);
        if (cells.isEmpty()) {
            return new DataCoverage(null, null, 0);
        }
        OffsetDateTime minStart = cells.stream()
                .map(DataCell::getWindowStart)
                .min(Comparator.naturalOrder())
                .orElse(null);
        OffsetDateTime maxEnd = cells.stream()
                .map(DataCell::getWindowEnd)
                .max(Comparator.naturalOrder())
                .orElse(null);
        int totalEvents = cells.stream().mapToInt(DataCell::getEventCount).sum();
        return new DataCoverage(minStart, maxEnd, totalEvents);
    }

    private List<IssueSummary> buildTopIssues(Long workspaceId, AnalysisWindow window) {
        List<IssueMetricBucket> recentBuckets = issueMetricBucketRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, window.start())
                .stream()
                .filter(bucket -> !bucket.getBucketStart().isAfter(window.end()))
                .toList();

        Map<Long, Integer> countByIssue = recentBuckets.stream()
                .collect(Collectors.groupingBy(
                        IssueMetricBucket::getIssueId,
                        Collectors.summingInt(IssueMetricBucket::getFeedbackCount)));

        List<Long> topIssueIds = countByIssue.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_ISSUES_LIMIT)
                .map(Map.Entry::getKey)
                .toList();

        if (topIssueIds.isEmpty()) {
            return List.of();
        }

        Map<Long, IssueCatalog> catalogById = issueCatalogRepository.findAllById(topIssueIds)
                .stream()
                .collect(Collectors.toMap(IssueCatalog::getId, catalog -> catalog));

        return topIssueIds.stream()
                .map(issueId -> {
                    IssueCatalog catalog = catalogById.get(issueId);
                    return new IssueSummary(
                            catalog.getPublicId(),
                            catalog.getCanonicalKey(),
                            catalog.getCanonicalName(),
                            countByIssue.get(issueId));
                })
                .toList();
    }

    private List<AlertSummary> buildRecentAlerts(Long workspaceId) {
        return alertRepository.findTop5ByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
                .stream()
                .map(this::toAlertSummary)
                .toList();
    }

    private AlertSummary toAlertSummary(Alert alert) {
        String issueName = issueCatalogRepository.findById(alert.getIssueId())
                .map(IssueCatalog::getCanonicalName).orElse("未知");
        String issueKey = issueCatalogRepository.findById(alert.getIssueId())
                .map(IssueCatalog::getCanonicalKey).orElse("unknown");
        return new AlertSummary(
                alert.getPublicId(),
                issueName,
                issueKey,
                alert.getCurrentCount(),
                alert.getCreatedAt());
    }

    private BaselineStatus buildBaselineStatus(Long workspaceId) {
        List<IssueBaselineProfile> profiles = issueBaselineProfileRepository.findByWorkspaceId(workspaceId);
        int building = 0;
        int active = 0;
        for (IssueBaselineProfile profile : profiles) {
            if ("active".equals(profile.getStatus())) {
                active++;
            } else {
                building++;
            }
        }
        return new BaselineStatus(building, active);
    }

    private ProjectionSummary buildLatestProjection(Long workspaceId) {
        return workspaceProjectionRepository.findTopByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
                .map(projection -> new ProjectionSummary(
                        projection.getPublicId(),
                        projection.getStatus(),
                        projection.getProjectedAt()))
                .orElse(null);
    }

    private BaselineInfo toBaselineInfo(IssueBaselineProfile profile) {
        return new BaselineInfo(profile.getStatus(), profile.getBaselineEwma(), profile.baselineStddev());
    }

    private IssueSummary toIssueSummary(IssueCatalog catalog, List<IssueMetricBucket> buckets) {
        int totalCount = buckets.stream().mapToInt(IssueMetricBucket::getFeedbackCount).sum();
        return new IssueSummary(catalog.getPublicId(), catalog.getCanonicalKey(), catalog.getCanonicalName(), totalCount);
    }

    /**
     * 看板首页响应；expressionSummary 为 spec 修订后的首屏主视图（L2 分布/趋势），
     * topIssues 等既有字段保留供 L1 场景（如告警列表跳转）继续使用。
     */
    public record DashboardResponse(
            DataCoverage coverage,
            WindowInfo analysisWindow,
            List<IssueSummary> topIssues,
            List<AlertSummary> recentAlerts,
            BaselineStatus baselineStatus,
            ProjectionSummary latestProjection,
            ExpressionSummary expressionSummary) {
    }

    /** 实际生效的分析日期范围；与请求参数或默认规则一致，供前端回显。 */
    public record WindowInfo(OffsetDateTime start, OffsetDateTime end) {
    }

    /** 脱敏样本及反馈发生时间与来源，供人工核验分类质量。 */
    public record FeedbackSample(String text, OffsetDateTime occurredAt, String sourceKind) {
    }

    /**
     * 看板首屏 L2 表达层汇总：五类占比、7 天趋势、L1 待复核次要 KPI、当前绑定的 Topic Pack。
     */
    public record ExpressionSummary(
            List<ExpressionCount> distribution,
            List<ExpressionTrendPoint> trend,
            int reviewPendingCount,
            String topicPackId,
            String topicPackVersion) {
    }

    /** 单个 L2 类目的计数；key 为稳定枚举如 expr_suggestion，name 为中文展示名。 */
    public record ExpressionCount(String key, String name, int feedbackCount) {
    }

    /** 某天的 L2 五类计数快照；countsByExpression 缺失的键表示当天该类目计数为 0。 */
    public record ExpressionTrendPoint(OffsetDateTime bucketStart, Map<String, Integer> countsByExpression) {
    }

    /**
     * L2→L1 钻取响应：某表达类目下，Workspace 当前 Topic Pack 内的议题分布。
     */
    public record ExpressionTopicsResponse(
            String expressionKey,
            String topicPackId,
            String topicPackVersion,
            List<TopicCount> topics,
            WindowInfo analysisWindow) {
    }

    /** 钻取结果中的单个 L1 议题计数；topicId 为对外 public_id，不暴露内部自增主键。 */
    public record TopicCount(UUID topicId, String canonicalKey, String canonicalName, int feedbackCount) {
    }

    /**
     * alert_eligible 子集概览响应：Pack 内可行动议题的窗口内计数、趋势与最近告警（只读）。
     */
    public record AlertEligibleOverviewResponse(
            WindowInfo analysisWindow,
            int totalFeedbackCount,
            int eligibleTopicCount,
            List<AlertEligibleTopicSummary> topics,
            List<AlertEligibleTrendPoint> trend,
            List<AlertSummary> recentAlerts,
            String topicPackId,
            String topicPackVersion) {
    }

    /** 单个 alert_eligible 议题的窗口内计数与简易趋势方向。 */
    public record AlertEligibleTopicSummary(
            UUID topicId, String canonicalKey, String canonicalName, int feedbackCount, String trendDirection) {
    }

    /** alert_eligible 子集按日聚合的趋势点。 */
    public record AlertEligibleTrendPoint(OffsetDateTime bucketStart, int feedbackCount) {
    }

    /**
     * 数据覆盖范围。
     */
    public record DataCoverage(OffsetDateTime windowStart, OffsetDateTime windowEnd, int totalEvents) {
    }

    /**
     * Issue 摘要。
     */
    public record IssueSummary(UUID issueId, String canonicalKey, String canonicalName, int feedbackCount) {
    }

    /**
     * 告警摘要；不包含内部 issue 键，前端通过 canonicalKey 路由。
     */
    public record AlertSummary(UUID alertId, String issueName, String issueKey, int currentCount, OffsetDateTime createdAt) {
    }

    /**
     * 基线状态统计。
     */
    public record BaselineStatus(int building, int active) {
    }

    /**
     * 最新投影摘要。
     */
    public record ProjectionSummary(UUID projectionId, String status, OffsetDateTime projectedAt) {
    }

    /**
     * Issue 详情响应。
     */
    public record IssueDetailResponse(
            UUID issueId,
            String canonicalKey,
            String canonicalName,
            String status,
            List<TrendPoint> recentTrend,
            List<AlertSummary> alerts,
            BaselineInfo baseline,
            List<FeedbackSample> samples,
            WindowInfo analysisWindow) {
    }

    /**
     * 趋势点。
     */
    public record TrendPoint(OffsetDateTime bucketStart, int feedbackCount) {
    }

    /**
     * 基线信息。
     */
    public record BaselineInfo(String status, double ewma, double stddev) {
    }
}
