package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.common.exception.IssueNotFoundException;
import com.insightflow.entity.Alert;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.entity.Workspace;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.IssueBaselineProfileRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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

    private final DashboardService dashboardService = new DashboardService(
            workspaceService,
            dataCellRepository,
            issueMetricBucketRepository,
            alertRepository,
            issueBaselineProfileRepository,
            issueCatalogRepository,
            workspaceProjectionRepository);

    @Test
    void getDashboardReturnsAggregatedData() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 1L);

        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(dataCellRepository.findByWorkspaceId(1L)).thenReturn(List.of(
                DataCell.of(1L, 10L, OffsetDateTime.now().minusDays(2), OffsetDateTime.now(), "stream_end", 5, 100)));
        when(issueMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(any(), any()))
                .thenReturn(List.of(bucket(1L, 1L, 10)));
        IssueCatalog catalog = catalog(1L, "login_failure", "登录失败");
        when(issueCatalogRepository.findAllById(List.of(1L))).thenReturn(List.of(catalog));
        when(issueCatalogRepository.findByWorkspaceId(1L)).thenReturn(List.of(catalog));
        when(alertRepository.findTop5ByWorkspaceIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(alert(1L, 1L, 7)));
        when(issueBaselineProfileRepository.findByWorkspaceId(1L)).thenReturn(List.of(
                profile(1L, 1L, "active")));
        when(workspaceProjectionRepository.findTopByWorkspaceIdOrderByCreatedAtDesc(1L))
                .thenReturn(Optional.of(projection(1L, 1L, "succeeded")));

        DashboardService.DashboardResponse response = dashboardService.getDashboard(workspacePublicId);

        assertThat(response).isNotNull();
        assertThat(response.coverage().totalEvents()).isEqualTo(5);
        assertThat(response.topIssues()).hasSize(1);
        assertThat(response.recentAlerts()).hasSize(1);
        assertThat(response.baselineStatus().active()).isEqualTo(1);
        assertThat(response.latestProjection().status()).isEqualTo("succeeded");
    }

    @Test
    void getIssuesReturnsSortedIssueSummaries() throws Exception {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = workspaceWithId(workspacePublicId, 2L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);

        IssueCatalog first = catalog(10L, "login_failure", "登录失败");
        IssueCatalog second = catalog(11L, "checkout_error", "结账失败");
        when(issueCatalogRepository.findByWorkspaceId(2L)).thenReturn(List.of(first, second));
        when(issueMetricBucketRepository.findByWorkspaceIdAndBucketStartGreaterThanEqual(2L, OffsetDateTime.MIN))
                .thenReturn(List.of(
                        bucket(10L, 2L, 5),
                        bucket(11L, 2L, 10),
                        bucket(10L, 2L, 3)));

        List<DashboardService.IssueSummary> issues = dashboardService.getIssues(workspacePublicId);

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

        DashboardService.IssueDetailResponse detail = dashboardService.getIssueDetail(workspacePublicId, "login_failure");

        assertThat(detail.canonicalKey()).isEqualTo("login_failure");
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
                () -> dashboardService.getIssueDetail(workspacePublicId, "missing"))
                .isInstanceOf(IssueNotFoundException.class);
    }

    private Workspace workspaceWithId(UUID publicId, long id) throws Exception {
        Workspace workspace = new Workspace("test");
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

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
