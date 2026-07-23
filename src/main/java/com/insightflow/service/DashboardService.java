package com.insightflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.common.exception.IssueNotFoundException;
import com.insightflow.entity.Alert;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.FeedbackEvent;
import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.entity.Workspace;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.repository.IssueBaselineProfileRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final int DASHBOARD_DAYS = 7;
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
            ObjectMapper objectMapper) {
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
    }

    /**
     * 聚合看板首页所需数据：数据覆盖、Top 5 问题、最近告警、基线状态、最新投影。
     */
    public DashboardResponse getDashboard(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();

        DataCoverage coverage = buildDataCoverage(workspaceId);
        List<IssueSummary> topIssues = buildTopIssues(workspaceId);
        List<AlertSummary> recentAlerts = buildRecentAlerts(workspaceId);
        BaselineStatus baselineStatus = buildBaselineStatus(workspaceId);
        ProjectionSummary latestProjection = buildLatestProjection(workspaceId);

        return new DashboardResponse(coverage, topIssues, recentAlerts, baselineStatus, latestProjection);
    }

    /**
     * 返回工作区下所有 issue 的汇总列表，按总反馈数降序排列。
     */
    public List<IssueSummary> getIssues(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();

        Map<Long, List<IssueMetricBucket>> bucketsByIssue = issueMetricBucketRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId,
                        OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                .stream()
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
    public IssueDetailResponse getIssueDetail(UUID workspacePublicId, String canonicalKey) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        Long workspaceId = workspace.getId();

        IssueCatalog catalog = issueCatalogRepository.findByWorkspaceIdAndCanonicalKey(workspaceId, canonicalKey)
                .orElseThrow(() -> new IssueNotFoundException(canonicalKey));

        OffsetDateTime sevenDaysAgo = OffsetDateTime.now().minusDays(DASHBOARD_DAYS);
        List<IssueMetricBucket> recentBuckets = issueMetricBucketRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, sevenDaysAgo)
                .stream()
                .filter(bucket -> bucket.getIssueId().equals(catalog.getId()))
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

        // Sample texts from cell_issue sample_event_ids
        List<String> sampleTexts = cellIssueRepository.findByIssueId(catalog.getId()).stream()
                .flatMap(ci -> {
                    try {
                        return objectMapper.readValue(ci.getSampleEventIdsJson(), new TypeReference<List<Long>>() {}).stream();
                    } catch (Exception e) { return java.util.stream.Stream.empty(); }
                })
                .distinct().limit(5)
                .map(eventId -> feedbackEventRepository.findById(eventId).map(FeedbackEvent::getSanitizedText).orElse(null))
                .filter(t -> t != null)
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
                sampleTexts);
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

    private List<IssueSummary> buildTopIssues(Long workspaceId) {
        OffsetDateTime sevenDaysAgo = OffsetDateTime.now().minusDays(DASHBOARD_DAYS);
        List<IssueMetricBucket> recentBuckets = issueMetricBucketRepository
                .findByWorkspaceIdAndBucketStartGreaterThanEqual(workspaceId, sevenDaysAgo);

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
     * 看板首页响应。
     */
    public record DashboardResponse(
            DataCoverage coverage,
            List<IssueSummary> topIssues,
            List<AlertSummary> recentAlerts,
            BaselineStatus baselineStatus,
            ProjectionSummary latestProjection) {
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
            List<String> sampleTexts) {
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
