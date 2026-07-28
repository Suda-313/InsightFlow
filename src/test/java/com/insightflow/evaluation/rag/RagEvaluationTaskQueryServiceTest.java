package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.common.exception.RagEvaluationTaskNotFoundException;
import com.insightflow.entity.Workspace;
import com.insightflow.security.WorkspaceAccessService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** RAG 任务查询必须遵守与会话、Trace 一致的跨 Workspace 隐藏语义。 */
class RagEvaluationTaskQueryServiceTest {

    /**
     * 同一个任务 UUID 不属于当前工作区时，不能作为参数错误暴露给客户端；
     * 否则调用方能够利用 422 与真实任务的成功响应探测其他工作区的任务。
     */
    @Test
    void hidesTaskOutsideCurrentWorkspaceAsNotFound() {
        UUID workspacePublicId = UUID.randomUUID();
        UUID taskPublicId = UUID.randomUUID();
        WorkspaceAccessService accessService = mock(WorkspaceAccessService.class);
        RagEvaluationTaskService taskService = mock(RagEvaluationTaskService.class);
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(42L);
        when(accessService.requireRead(workspacePublicId)).thenReturn(workspace);
        when(taskService.findWorkspaceTask(42L, taskPublicId)).thenReturn(Optional.empty());

        RagEvaluationTaskQueryService service = new RagEvaluationTaskQueryService(accessService, taskService);

        assertThatThrownBy(() -> service.get(workspacePublicId, taskPublicId))
                .isInstanceOf(RagEvaluationTaskNotFoundException.class);
    }
}
