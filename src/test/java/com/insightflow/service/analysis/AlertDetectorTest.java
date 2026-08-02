package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.repository.AlertRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AlertDetector 的行为测试。
 */
class AlertDetectorTest {

    private static final Long WORKSPACE_ID = 1L;
    private static final Long ISSUE_ID = 2L;
    private static final Long PROJECTION_ID = 3L;
    private static final OffsetDateTime BUCKET_START = OffsetDateTime.parse("2026-07-21T00:00:00Z");
    private static final int COOLDOWN_HOURS = 24;
    private static final int GLOBAL_ALERT_THRESHOLD = 5;
    private static final double SURGE_Z = 2.0;

    private final AlertRepository alertRepository = mock(AlertRepository.class);
    private final EwmaBaselineService ewmaBaselineService = mock(EwmaBaselineService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AlertDetector alertDetector = new AlertDetector(
            alertRepository, ewmaBaselineService, COOLDOWN_HOURS, GLOBAL_ALERT_THRESHOLD, SURGE_Z,
            objectMapper);

    /** 超过动态阈值时应触发告警。 */
    @Test
    void triggersAlertWhenAboveThreshold() {
        IssueBaselineProfile baseline = mock(IssueBaselineProfile.class);
        when(baseline.getBaselineEwma()).thenReturn(10.0);
        when(baseline.baselineStddev()).thenReturn(2.0);
        when(baseline.getActiveBuckets()).thenReturn(5);
        when(baseline.getClassification()).thenReturn("surge");
        when(ewmaBaselineService.getSurgeZ()).thenReturn(SURGE_Z);
        when(alertRepository.findTopByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(WORKSPACE_ID, ISSUE_ID))
                .thenReturn(Optional.empty());
        when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Alert> result = alertDetector.detect(
                WORKSPACE_ID, ISSUE_ID, PROJECTION_ID, BUCKET_START, 15, baseline);

        assertThat(result).isPresent();
        Alert alert = result.get();
        assertThat(alert.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(alert.getIssueId()).isEqualTo(ISSUE_ID);
        assertThat(alert.getWorkspaceProjectionId()).isEqualTo(PROJECTION_ID);
        assertThat(alert.getCurrentCount()).isEqualTo(15);
        assertThat(alert.getBaselineEwma()).isEqualTo(10.0);
        assertThat(alert.getBaselineStddev()).isEqualTo(2.0);
        assertThat(alert.getEffectiveThreshold()).isEqualTo(14);
        assertThat(alert.getStatus()).isEqualTo("active");
        assertThat(alert.getEvidenceJson()).contains("\"bucket_start\":");
        verify(alertRepository).save(any(Alert.class));
    }

    /** 低于生效阈值时不应触发告警。 */
    @Test
    void skipsWhenBelowThreshold() {
        IssueBaselineProfile baseline = mock(IssueBaselineProfile.class);
        when(baseline.getBaselineEwma()).thenReturn(10.0);
        when(baseline.baselineStddev()).thenReturn(2.0);
        when(baseline.getActiveBuckets()).thenReturn(5);
        when(baseline.getClassification()).thenReturn("normal");
        when(ewmaBaselineService.getSurgeZ()).thenReturn(SURGE_Z);

        Optional<Alert> result = alertDetector.detect(
                WORKSPACE_ID, ISSUE_ID, PROJECTION_ID, BUCKET_START, 5, baseline);

        assertThat(result).isEmpty();
        verify(alertRepository, never()).save(any());
    }

    /** 冷却期内不应重复触发告警。 */
    @Test
    void skipsWhenInCooldown() {
        IssueBaselineProfile baseline = mock(IssueBaselineProfile.class);
        when(baseline.getBaselineEwma()).thenReturn(10.0);
        when(baseline.baselineStddev()).thenReturn(2.0);
        when(baseline.getActiveBuckets()).thenReturn(5);
        when(baseline.getClassification()).thenReturn("surge");
        when(ewmaBaselineService.getSurgeZ()).thenReturn(SURGE_Z);

        Alert lastAlert = Alert.active(
                WORKSPACE_ID, ISSUE_ID, PROJECTION_ID, BUCKET_START.minusDays(1), 20,
                10.0, 2.0, 5.0, 14, "{}");
        when(alertRepository.findTopByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(WORKSPACE_ID, ISSUE_ID))
                .thenReturn(Optional.of(lastAlert));

        Optional<Alert> result = alertDetector.detect(
                WORKSPACE_ID, ISSUE_ID, PROJECTION_ID, BUCKET_START, 15, baseline);

        assertThat(result).isEmpty();
        verify(alertRepository, never()).save(any());
    }
}
