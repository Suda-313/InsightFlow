package com.insightflow.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.risk.RiskLevel;
import com.insightflow.risk.RiskPriority;
import com.insightflow.risk.RiskPrioritySnapshot;
import com.insightflow.service.WorkspaceService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证时间段报告使用告警创建时冻结的风险等级，而不是当前队列状态。 */
class OperationalReportRiskAssemblerTest {

    private final WorkspaceService workspaceService = org.mockito.Mockito.mock(WorkspaceService.class);
    private final AlertRepository alertRepository = org.mockito.Mockito.mock(AlertRepository.class);
    private final RiskPrioritySnapshotRepository snapshotRepository = org.mockito.Mockito.mock(RiskPrioritySnapshotRepository.class);
    private final IssueCatalogRepository issueCatalogRepository = org.mockito.Mockito.mock(IssueCatalogRepository.class);
    private final OperationalReportRiskAssembler assembler = new OperationalReportRiskAssembler(
            workspaceService, alertRepository, snapshotRepository, issueCatalogRepository);

    @Test
    void returnsOnlyRisksCreatedWithinRequestedTimeRange() {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = new Workspace("report-workspace", 1L);
        ReflectionTestUtils.setField(workspace, "id", 7L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);

        OffsetDateTime start = OffsetDateTime.parse("2026-08-01T00:00:00+08:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-08T00:00:00+08:00");
        Alert alert = Alert.active(7L, 9L, 3L, start.plusDays(2), 30, 10, 2, 8, 5, "{}");
        ReflectionTestUtils.setField(alert, "id", 22L);
        ReflectionTestUtils.setField(alert, "createdAt", start.plusDays(2));
        when(alertRepository.findByWorkspaceIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                7L, start, end)).thenReturn(List.of(alert));

        RiskPrioritySnapshot snapshot = RiskPrioritySnapshot.create(7L, 22L,
                new RiskPriority(RiskLevel.P0, 92, List.of("异常强度高")));
        when(snapshotRepository.findByWorkspaceIdAndAlertId(7L, 22L)).thenReturn(Optional.of(snapshot));
        IssueCatalog issue = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(issueCatalogRepository.findById(9L)).thenReturn(Optional.of(issue));

        List<OperationalReportRiskAssembler.ReportRisk> risks = assembler.forTimeRange(workspacePublicId, start, end);

        assertThat(risks).singleElement().satisfies(risk -> {
            assertThat(risk.level()).isEqualTo(RiskLevel.P0);
            assertThat(risk.issueName()).isEqualTo("登录失败");
            assertThat(risk.score()).isEqualTo(92);
            assertThat(risk.createdAt()).isEqualTo(start.plusDays(2));
        });
    }
}
