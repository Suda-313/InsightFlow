package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.security.WorkspaceAccessService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RAG 评测提交只能创建当前已授权 Workspace 的受控异步任务，不能在 HTTP 请求线程内直接调用模型。
 */
class RagEvaluationTaskCommandServiceTest {

    @Test
    void enqueuesWorkspaceScopedRagEvaluationTask() {
        UUID workspacePublicId = UUID.randomUUID();
        WorkspaceAccessService accessService = mock(WorkspaceAccessService.class);
        AsyncTaskRepository tasks = mock(AsyncTaskRepository.class);
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(42L);
        when(accessService.requireRead(workspacePublicId)).thenReturn(workspace);
        when(tasks.save(any(AsyncTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AsyncTask task = new RagEvaluationTaskCommandService(accessService, tasks).enqueue(workspacePublicId);

        assertThat(task.getWorkspaceId()).isEqualTo(42L);
        assertThat(task.getTaskType()).isEqualTo("rag_evaluation");
        assertThat(task.getStatus()).isEqualTo("queued");
        assertThat(task.getPayloadJson()).isEqualTo("{}");
    }
}
