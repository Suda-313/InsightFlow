package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.repository.IssueBaselineProfileRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * EWMA 基线服务的行为测试。
 */
class EwmaBaselineServiceTest {

    private static final double ALPHA = 0.3;
    private static final int MIN_HISTORY_DAYS = 3;
    private static final double SURGE_Z = 2.0;
    private static final int SURGE_MIN = 5;
    private static final double CHRONIC_BASELINE = 5.0;
    private static final int LONGTAIL_MAX = 2;

    private final IssueBaselineProfileRepository repository = mock(IssueBaselineProfileRepository.class);
    private final EwmaBaselineService service = new EwmaBaselineService(
            repository, ALPHA, MIN_HISTORY_DAYS, SURGE_Z, SURGE_MIN, CHRONIC_BASELINE, LONGTAIL_MAX);

    /** 首个桶创建新 profile。 */
    @Test
    void createsProfileOnFirstBucket() {
        Long workspaceId = 1L;
        Long issueId = 2L;
        OffsetDateTime bucketStart = OffsetDateTime.parse("2026-07-21T00:00:00Z");

        when(repository.findByWorkspaceIdAndIssueId(workspaceId, issueId)).thenReturn(Optional.empty());
        when(repository.save(any(IssueBaselineProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        IssueBaselineProfile result = service.update(workspaceId, issueId, bucketStart, 3);

        assertThat(result).isNotNull();
        assertThat(result.getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(result.getIssueId()).isEqualTo(issueId);
        assertThat(result.getActiveBuckets()).isEqualTo(1);
        assertThat(result.getBaselineEwma()).isEqualTo(3.0);
        assertThat(result.getStatus()).isEqualTo("baseline_building");
        assertThat(result.getClassification()).isEqualTo("normal");
        verify(repository).save(any(IssueBaselineProfile.class));
    }

    /** 同一桶重复处理应幂等跳过。 */
    @Test
    void skipsSameBucket() {
        Long workspaceId = 1L;
        Long issueId = 2L;
        OffsetDateTime bucketStart = OffsetDateTime.parse("2026-07-21T00:00:00Z");
        IssueBaselineProfile existing = IssueBaselineProfile.create(
                workspaceId, issueId, bucketStart, 3, MIN_HISTORY_DAYS);

        when(repository.findByWorkspaceIdAndIssueId(workspaceId, issueId)).thenReturn(Optional.of(existing));

        IssueBaselineProfile result = service.update(workspaceId, issueId, bucketStart, 10);

        assertThat(result.getActiveBuckets()).isEqualTo(1);
        assertThat(result.getBaselineEwma()).isEqualTo(3.0);
        verify(repository, never()).save(any());
    }

    /** 满 3 个桶后状态切换为 active。 */
    @Test
    void becomesActiveAfterMinHistoryDays() {
        Long workspaceId = 1L;
        Long issueId = 2L;
        OffsetDateTime bucketStart = OffsetDateTime.parse("2026-07-21T00:00:00Z");
        IssueBaselineProfile existing = IssueBaselineProfile.create(
                workspaceId, issueId, bucketStart, 3, MIN_HISTORY_DAYS);
        existing.updateEwma(ALPHA, 3, bucketStart.plusDays(1), MIN_HISTORY_DAYS, "normal");

        when(repository.findByWorkspaceIdAndIssueId(workspaceId, issueId)).thenReturn(Optional.of(existing));
        when(repository.save(any(IssueBaselineProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        IssueBaselineProfile result = service.update(workspaceId, issueId, bucketStart.plusDays(2), 3);

        assertThat(result.getActiveBuckets()).isEqualTo(3);
        assertThat(result.getStatus()).isEqualTo("active");
    }

    /** z >= 2.0 且 count >= 5 应被分类为 surge。 */
    @Test
    void classifiesSurge() {
        Long workspaceId = 1L;
        Long issueId = 2L;
        OffsetDateTime bucketStart = OffsetDateTime.parse("2026-07-21T00:00:00Z");
        IssueBaselineProfile existing = IssueBaselineProfile.create(
                workspaceId, issueId, bucketStart, 3, MIN_HISTORY_DAYS);
        existing.updateEwma(ALPHA, 3, bucketStart.plusDays(1), MIN_HISTORY_DAYS, "normal");

        when(repository.findByWorkspaceIdAndIssueId(workspaceId, issueId)).thenReturn(Optional.of(existing));
        when(repository.save(any(IssueBaselineProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        IssueBaselineProfile result = service.update(workspaceId, issueId, bucketStart.plusDays(2), 10);

        assertThat(result.getClassification()).isEqualTo("surge");
    }
}
