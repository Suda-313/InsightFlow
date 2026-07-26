package com.insightflow.evaluation.rag;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.security.WorkspaceAccessService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG 评测任务的受理边界。
 *
 * <p>HTTP 请求只创建轻量的持久化命令，不在 Web 线程调用嵌入或聊天模型。
 * Worker 领取任务后才读取当前 Workspace 的已发布知识，从而避免请求超时和跨 Workspace 执行。</p>
 */
@Service
public class RagEvaluationTaskCommandService {

    /**
     * 访问服务先完成当前登录用户的组织与 Workspace 读权限校验。
     * 任务仓储只能使用校验后 Workspace 的内部键写入隔离字段。
     */
    private final WorkspaceAccessService workspaceAccessService;
    private final AsyncTaskRepository taskRepository;

    /**
     * 显式注入权限和持久化边界，避免 Controller 直接写任务表绕过 Workspace 校验。
     */
    public RagEvaluationTaskCommandService(
            WorkspaceAccessService workspaceAccessService,
            AsyncTaskRepository taskRepository) {
        this.workspaceAccessService = workspaceAccessService;
        this.taskRepository = taskRepository;
    }

    /**
     * 受理一次新的 RAG 评测命令。
     *
     * <p>每次人工点击均产生独立批次，用于保留知识版本变化后的可比较历史；
     * 幂等键仅用于数据库任务约束，不从客户端接收，避免重放旧命令覆盖新基线。</p>
     */
    @Transactional
    public AsyncTask enqueue(UUID workspacePublicId) {
        Workspace workspace = workspaceAccessService.requireRead(workspacePublicId);
        AsyncTask task = AsyncTask.queuedRagEvaluation(workspace.getId(), UUID.randomUUID().toString());
        return taskRepository.save(task);
    }
}
