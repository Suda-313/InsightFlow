package com.insightflow.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Workspace;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 成员管理服务的权限与重复授权回归测试；测试文件仅在本地保留，不进入本次提交。 */
@ExtendWith(MockitoExtension.class)
class MemberManagementServiceTest {

    @Mock private WorkspaceAccessService accessService;
    @Mock private AppUserRepository userRepository;
    @Mock private OrganizationMemberRepository organizationMemberRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @InjectMocks private MemberManagementService service;

    /** Owner 创建成员时，必须同时建立组织角色与当前 Workspace 范围。 */
    @Test
    void grantsOrganizationRoleAndWorkspaceScopeForNewMember() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(workspace.getOrganizationId()).thenReturn(10L);
        when(workspace.getId()).thenReturn(20L);
        when(accessService.requireRole(workspaceId, MemberRole.OWNER)).thenReturn(workspace);
        when(userRepository.existsByUsername("operator")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.grantNewMember(workspaceId, " Operator ", "password123", MemberRole.OPERATOR);

        verify(organizationMemberRepository).save(any(OrganizationMember.class));
        verify(workspaceMemberRepository).save(any(WorkspaceMember.class));
    }

    /** 用户名已经存在时拒绝创建，避免借由同名账户覆盖或扩展既有授权。 */
    @Test
    void rejectsExistingUsername() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(accessService.requireRole(workspaceId, MemberRole.OWNER)).thenReturn(workspace);
        when(userRepository.existsByUsername("operator")).thenReturn(true);

        assertThatThrownBy(() -> service.grantNewMember(workspaceId, "operator", "password123", MemberRole.VIEWER))
                .isInstanceOf(MembershipConflictException.class);

        verify(organizationMemberRepository, org.mockito.Mockito.never()).save(any());
    }
}
