package com.insightflow.evaluation.rag;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.Workspace;
import com.insightflow.common.exception.RagEvaluationTaskNotFoundException;
import com.insightflow.security.WorkspaceAccessService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** RAG 任务轮询查询必须先做 Workspace 授权，再用内部隔离键读取任务。*/
@Service
public class RagEvaluationTaskQueryService {
    private final WorkspaceAccessService accessService;
    private final RagEvaluationTaskService taskService;

    /** 授权和任务查询分离，避免 Worker 依赖当前用户上下文。*/
    public RagEvaluationTaskQueryService(WorkspaceAccessService accessService, RagEvaluationTaskService taskService) {
        this.accessService = accessService;
        this.taskService = taskService;
    }

    /** 不存在、跨 Workspace 或非 RAG 类型的任务统一返回空，防止任务 UUID 被用于探测。*/
    public AsyncTask get(UUID workspacePublicId, UUID taskPublicId) {
        Workspace workspace = accessService.requireRead(workspacePublicId);
        return taskService.findWorkspaceTask(workspace.getId(), taskPublicId)
                .orElseThrow(() -> new RagEvaluationTaskNotFoundException(taskPublicId));
    }
}
