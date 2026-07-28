package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AuditLog;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AuditLogRepository;
import com.insightflow.security.CurrentUser;
import com.insightflow.security.WorkspaceAccessService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 审计服务的隔离与脱敏回归测试；测试文件仅在本地保留，不进入本次提交。 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock private WorkspaceAccessService accessService;
    @Mock private CurrentUser currentUser;
    @Mock private AuditLogRepository auditLogRepository;
    @InjectMocks private AuditLogService service;

    /** 审计记录必须绑定授权后的内部 Workspace 与当前操作者，同时拒绝原始密码等敏感摘要。 */
    @Test
    void storesActorAndWorkspaceWithoutRawCommandPayload() {
        UUID workspaceId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(workspace.getId()).thenReturn(9L);
        when(accessService.requireRead(workspaceId)).thenReturn(workspace);
        when(currentUser.requirePublicId()).thenReturn(actorId);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog log = service.record(workspaceId, "proposal.executed", proposalId, "action=CONFIRM");

        assertThat(log.getWorkspaceId()).isEqualTo(9L);
        assertThat(log.getActorUserPublicId()).isEqualTo(actorId);
        assertThat(log.getSummary()).doesNotContain("password");
    }
}
