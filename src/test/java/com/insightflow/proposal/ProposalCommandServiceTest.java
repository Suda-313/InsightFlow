package com.insightflow.proposal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.ActionExecution;
import com.insightflow.entity.ActionProposal;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.ActionExecutionRepository;
import com.insightflow.repository.ActionProposalRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.security.MemberRole;
import com.insightflow.security.WorkspaceAccessService;
import com.insightflow.service.AuditLogService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 提案命令幂等性回归测试；测试文件仅在本地保留，不进入本次提交。 */
@ExtendWith(MockitoExtension.class)
class ProposalCommandServiceTest {

    @Mock private WorkspaceAccessService accessService;
    @Mock private InvestigationCaseRepository caseRepository;
    @Mock private ActionProposalRepository proposalRepository;
    @Mock private ActionExecutionRepository executionRepository;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private ProposalCommandService service;

    /** 相同幂等键重复确认时，只能新增一条执行记录。 */
    @Test
    void executesProposalOnlyOnceForSameIdempotencyKey() {
        UUID workspaceId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(workspace.getId()).thenReturn(7L);
        when(accessService.requireRole(workspaceId, MemberRole.OWNER, MemberRole.OPERATOR)).thenReturn(workspace);
        when(executionRepository.findByWorkspaceIdAndIdempotencyKey(7L, "confirm-001"))
                .thenReturn(Optional.of(ActionExecution.executed(7L, caseId, proposalId, null, "confirm-001", "确认调查")));

        service.execute(workspaceId, caseId, proposalId, "confirm-001");
        service.execute(workspaceId, caseId, proposalId, "confirm-001");

        verify(executionRepository, times(0)).save(any(ActionExecution.class));
    }

    /** 撤销后必须让同一提案恢复待复核，否则页面会显示可复核但再也无法确认。 */
    @Test
    void undoReopensTheOriginalProposalForReview() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(workspace.getId()).thenReturn(7L);
        InvestigationCase investigation = InvestigationCase.queued(7L, 11L);
        ReflectionTestUtils.setField(investigation, "id", 12L);
        investigation.markPendingReview("证据已冻结");
        investigation.markConfirmed();
        ActionProposal proposal = ActionProposal.pending(7L, 12L, com.insightflow.entity.ProposalAction.CONFIRM, "确认", "依据", "{}");
        ReflectionTestUtils.setField(proposal, "id", 13L);
        proposal.markExecuted();
        ActionExecution execution = ActionExecution.executed(7L, 12L, 13L, UUID.randomUUID(), "undo-001", com.insightflow.entity.ProposalAction.CONFIRM, "action=CONFIRM");

        when(accessService.requireRole(workspaceId, MemberRole.OWNER, MemberRole.OPERATOR)).thenReturn(workspace);
        when(caseRepository.findByWorkspaceIdAndPublicId(7L, investigation.getPublicId())).thenReturn(Optional.of(investigation));
        when(executionRepository.findByWorkspaceIdAndPublicId(7L, execution.getPublicId())).thenReturn(Optional.of(execution));
        when(proposalRepository.findById(13L)).thenReturn(Optional.of(proposal));

        service.undo(workspaceId, investigation.getPublicId(), execution.getPublicId());

        assertThat(investigation.getStatus()).isEqualTo("pending_review");
        assertThat(proposal.getStatus()).isEqualTo("pending");
        assertThat(execution.getStatus()).isEqualTo("undone");
    }
}
