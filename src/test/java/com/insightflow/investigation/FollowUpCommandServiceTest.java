package com.insightflow.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.security.CurrentUser;
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

/**
 * 开始跟进是最小人工响应命令：必须经过 Workspace 授权和审计，
 * 但不建立排他责任人或修改调查 Worker 的取证状态。
 */
@ExtendWith(MockitoExtension.class)
class FollowUpCommandServiceTest {

    @Mock private WorkspaceAccessService accessService;
    @Mock private CurrentUser currentUser;
    @Mock private InvestigationCaseRepository caseRepository;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private FollowUpCommandService service;

    /** 具备运营或分析权限的成员可开始跟进，且操作人必须来自安全上下文。 */
    @Test
    void startsFollowUpForAuthorizedMemberAndWritesAuditFact() {
        UUID workspacePublicId = UUID.randomUUID();
        InvestigationCase investigation = InvestigationCase.queued(7L, 11L);
        UUID actorPublicId = UUID.randomUUID();
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(workspace.getId()).thenReturn(7L);
        when(accessService.requireRole(workspacePublicId, MemberRole.OWNER, MemberRole.ANALYST, MemberRole.OPERATOR))
                .thenReturn(workspace);
        when(caseRepository.findByWorkspaceIdAndPublicId(7L, investigation.getPublicId()))
                .thenReturn(Optional.of(investigation));
        when(caseRepository.save(any(InvestigationCase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentUser.requirePublicId()).thenReturn(actorPublicId);

        InvestigationCase result = service.start(workspacePublicId, investigation.getPublicId());

        assertThat(result.getFollowUpStatus()).isEqualTo("in_follow_up");
        assertThat(result.getFollowUpByUserPublicId()).isEqualTo(actorPublicId);
        verify(caseRepository).save(investigation);
        verify(auditLogService).record(workspacePublicId, "investigation.follow_up_started", investigation.getPublicId(), "follow_up=in_follow_up");
    }
}
