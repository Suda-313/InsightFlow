package com.insightflow.evaluation.rag;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.RagEvaluationRun;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.WorkspaceRepository;
import com.insightflow.service.RagEvaluationHistoryService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 独立线程池中的 RAG 评测 Worker，不在调度器或 HTTP 请求线程调用模型。*/
@Component
public class RagEvaluationTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(RagEvaluationTaskRunner.class);
    private final AsyncTaskRepository tasks;
    private final WorkspaceRepository workspaces;
    private final RagLiveEvaluationRunner runner;
    private final RagEvaluationHistoryService history;
    private final RagEvaluationTaskService taskService;

    /** Worker 读取的 Workspace 仅来自任务内部键，不能信任客户端传入的范围。*/
    public RagEvaluationTaskRunner(AsyncTaskRepository tasks, WorkspaceRepository workspaces, RagLiveEvaluationRunner runner,
                                   RagEvaluationHistoryService history, RagEvaluationTaskService taskService) {
        this.tasks = tasks; this.workspaces = workspaces; this.runner = runner; this.history = history; this.taskService = taskService;
    }

    /** 仅持有租约的任务可以执行；任何异常都会收敛为受控失败终态。*/
    @Async("ragEvaluationTaskExecutor")
    public void run(UUID taskPublicId, String workerId) {
        AsyncTask task = tasks.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !task.isLeaseOwnedBy(workerId) || !"rag_evaluation".equals(task.getTaskType())) return;
        try {
            var workspace = workspaces.findById(task.getWorkspaceId()).orElse(null);
            if (workspace == null) { taskService.fail(taskPublicId, workerId); return; }
            RagEvaluationRunResult result = runner.run(workspace.getPublicId());
            // 评测历史用于质量回归比较，任何题目因超时或供应商故障失败都会污染指标，不能降级为成功基线。
            if (result.caseResults().stream().anyMatch(item -> !"succeeded".equals(item.status()))) {
                taskService.markPartialFailed(taskPublicId, workerId);
                return;
            }
            RagEvaluationRun persisted = history.record(workspace.getPublicId(), result);
            taskService.succeed(taskPublicId, workerId, persisted.getPublicId());
        } catch (RuntimeException exception) {
            log.warn("RAG_EVAL task_id={}, status=failed, exception_type={}", taskPublicId, exception.getClass().getSimpleName());
            taskService.fail(taskPublicId, workerId);
        }
    }
}
