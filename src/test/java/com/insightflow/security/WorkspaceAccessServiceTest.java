package com.insightflow.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Workspace;
import com.insightflow.service.WorkspaceService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Workspace 访问服务的范围与角色回归测试；测试文件仅在本地保留，不进入本次提交。 */
@ExtendWith(MockitoExtension.class)
class WorkspaceAccessServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private CurrentUser currentUser;
    @Mock private AppUserRepository userRepository;
    @Mock private OrganizationMemberRepository organizationMemberRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @InjectMocks private WorkspaceAccessService accessService;

    /** 组织成员但未被授予当前 Workspace 的非 Owner 必须被拒绝。 */
    @Test
    void rejectsUserWithoutWorkspaceMembership() {
        UUID workspaceId = UUID.randomUUID();
        AppUser user = org.mockito.Mockito.mock(AppUser.class);
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(workspace.getId()).thenReturn(4L);
        when(workspaceService.get(workspaceId)).thenReturn(workspace);
        when(currentUser.requirePublicId()).thenReturn(UUID.randomUUID());
        when(user.getId()).thenReturn(2L);
        when(user.isDisabled()).thenReturn(false);
        when(userRepository.findByPublicId(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(user));
        when(organizationMemberRepository.findByUserIdAndOrganizationId(2L, 3L))
                .thenReturn(Optional.of(OrganizationMember.grant(3L, 2L, MemberRole.ANALYST)));
        when(workspaceMemberRepository.findByUserIdAndWorkspaceId(2L, 4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessService.requireRead(workspaceId))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
    }

    /** Owner 在组织内拥有所有 Workspace 的管理范围，不需要逐个重复授予成员记录。 */
    @Test
    void permitsOrganizationOwnerWithoutWorkspaceMembership() {
        UUID workspaceId = UUID.randomUUID();
        AppUser user = org.mockito.Mockito.mock(AppUser.class);
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(workspace.getOrganizationId()).thenReturn(3L);
        when(workspaceService.get(workspaceId)).thenReturn(workspace);
        when(currentUser.requirePublicId()).thenReturn(UUID.randomUUID());
        when(user.getId()).thenReturn(2L);
        when(user.isDisabled()).thenReturn(false);
        when(userRepository.findByPublicId(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(user));
        when(organizationMemberRepository.findByUserIdAndOrganizationId(2L, 3L))
                .thenReturn(Optional.of(OrganizationMember.grant(3L, 2L, MemberRole.OWNER)));

        accessService.requireRole(workspaceId, MemberRole.OWNER);
    }
}
