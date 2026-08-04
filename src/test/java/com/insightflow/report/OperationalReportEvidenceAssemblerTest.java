package com.insightflow.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.InvestigationEvidenceSnapshot;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.InvestigationEvidenceSnapshotRepository;
import com.insightflow.service.WorkspaceService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证运营报告只能引用用户选择时间段内冻结的调查证据。 */
class OperationalReportEvidenceAssemblerTest {

    private final WorkspaceService workspaceService = org.mockito.Mockito.mock(WorkspaceService.class);
    private final InvestigationCaseRepository caseRepository = org.mockito.Mockito.mock(InvestigationCaseRepository.class);
    private final InvestigationEvidenceSnapshotRepository evidenceRepository = org.mockito.Mockito.mock(InvestigationEvidenceSnapshotRepository.class);
    private final OperationalReportEvidenceAssembler assembler = new OperationalReportEvidenceAssembler(
            workspaceService, caseRepository, evidenceRepository);

    @Test
    void includesOnlyEvidenceCapturedWithinRequestedTimeRange() {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = new Workspace("report-workspace", 1L);
        ReflectionTestUtils.setField(workspace, "id", 7L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);

        InvestigationCase insideCase = InvestigationCase.queued(7L, 10L);
        InvestigationCase outsideCase = InvestigationCase.queued(7L, 11L);
        ReflectionTestUtils.setField(insideCase, "id", 101L);
        ReflectionTestUtils.setField(outsideCase, "id", 102L);
        when(caseRepository.findByWorkspaceIdAndStatusOrderByUpdatedAtDesc(7L, "confirmed"))
                .thenReturn(List.of(insideCase, outsideCase));

        OffsetDateTime start = OffsetDateTime.parse("2026-08-01T00:00:00+08:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-08-08T00:00:00+08:00");
        InvestigationEvidenceSnapshot inside = evidence(101L, 7L, "inside", start.plusDays(2));
        InvestigationEvidenceSnapshot outside = evidence(102L, 7L, "outside", end);
        when(evidenceRepository.findByInvestigationCaseIdAndWorkspaceIdOrderByCreatedAtAsc(101L, 7L))
                .thenReturn(List.of(inside));
        when(evidenceRepository.findByInvestigationCaseIdAndWorkspaceIdOrderByCreatedAtAsc(102L, 7L))
                .thenReturn(List.of(outside));

        List<OperationalReportEvidenceAssembler.ReportEvidence> evidence = assembler.forTimeRange(
                workspacePublicId, start, end, OperationalReportScope.WEEKLY);

        assertThat(evidence).extracting(OperationalReportEvidenceAssembler.ReportEvidence::title)
                .containsExactly("inside");
    }

    private InvestigationEvidenceSnapshot evidence(Long caseId, Long workspaceId, String title, OffsetDateTime capturedAt) {
        InvestigationEvidenceSnapshot snapshot = InvestigationEvidenceSnapshot.capture(
                caseId, workspaceId, "alert", "alert:test", title, "summary", true, null);
        ReflectionTestUtils.setField(snapshot, "createdAt", capturedAt);
        return snapshot;
    }
}
