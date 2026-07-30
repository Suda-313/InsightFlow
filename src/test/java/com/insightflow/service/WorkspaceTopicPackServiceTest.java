package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.Workspace;
import com.insightflow.repository.WorkspaceRepository;
import com.insightflow.security.WorkspaceAccessService;
import com.insightflow.service.analysis.TopicPackRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceTopicPackServiceTest {

    @Mock
    private WorkspaceAccessService accessService;

    @Mock
    private WorkspaceRepository workspaceRepository;

    private final TopicPackRegistry topicPackRegistry = topicPackRegistry();

    private static TopicPackRegistry topicPackRegistry() {
        TopicPackRegistry registry = new TopicPackRegistry("game-chaoziran");
        registry.load();
        return registry;
    }

    @Test
    void getBindingReturnsEffectivePack() {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = new Workspace("demo", 1L);
        when(accessService.requireRead(workspacePublicId)).thenReturn(workspace);

        WorkspaceTopicPackService service = new WorkspaceTopicPackService(
                accessService, workspaceRepository, topicPackRegistry);

        WorkspaceTopicPackService.TopicPackBinding binding = service.getBinding(workspacePublicId);

        assertThat(binding.packId()).isEqualTo("game-chaoziran");
        assertThat(binding.explicitlyBound()).isFalse();
    }

    @Test
    void bindPackPersistsWorkspaceBinding() {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = new Workspace("demo", 1L);
        when(accessService.requireRole(workspacePublicId, com.insightflow.security.MemberRole.OWNER,
                com.insightflow.security.MemberRole.OPERATOR)).thenReturn(workspace);
        when(accessService.requireRead(workspacePublicId)).thenReturn(workspace);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkspaceTopicPackService service = new WorkspaceTopicPackService(
                accessService, workspaceRepository, topicPackRegistry);

        WorkspaceTopicPackService.TopicPackBinding binding =
                service.bindPack(workspacePublicId, "game-chaoziran");

        assertThat(binding.packId()).isEqualTo("game-chaoziran");
        assertThat(binding.explicitlyBound()).isTrue();
        assertThat(workspace.getTopicPackId()).isEqualTo("game-chaoziran");
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void bindPackRejectsUnknownPack() {
        UUID workspacePublicId = UUID.randomUUID();
        Workspace workspace = new Workspace("demo", 1L);
        when(accessService.requireRole(workspacePublicId, com.insightflow.security.MemberRole.OWNER,
                com.insightflow.security.MemberRole.OPERATOR)).thenReturn(workspace);

        WorkspaceTopicPackService service = new WorkspaceTopicPackService(
                accessService, workspaceRepository, topicPackRegistry);

        assertThatThrownBy(() -> service.bindPack(workspacePublicId, "missing-pack"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
