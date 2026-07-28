package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Organization;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.OrganizationRepository;
import com.insightflow.repository.WorkspaceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Workspace 创建归属的回归测试。
 *
 * <p>P3 不引入组织管理页面，因此创建入口只能绑定唯一默认组织；不能让调用方伪造内部组织 ID，
 * 也不能留下组织为空的 Workspace。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private Organization defaultOrganization;

    /**
     * 创建 Workspace 必须从服务端解析默认组织，并把归属写入待持久化实体。
     */
    @Test
    void createAssignsTheDefaultOrganization() {
        when(organizationRepository.findByDefaultOrganizationTrue()).thenReturn(Optional.of(defaultOrganization));
        when(defaultOrganization.getId()).thenReturn(7L);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Workspace created = new WorkspaceService(workspaceRepository, organizationRepository).create(" 游戏 A ");

        assertThat(created.getName()).isEqualTo("游戏 A");
        assertThat(created.getOrganizationId()).isEqualTo(7L);
    }
}
