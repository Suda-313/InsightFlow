package com.insightflow.evaluation.rag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.RagEvaluationRun;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.WorkspaceRepository;
import com.insightflow.service.RagEvaluationHistoryService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 部分题目失败的批次只能表达为任务失败，不能污染可比较的 RAG 质量基线。 */
class RagEvaluationTaskRunnerTest {

    /**
     * 若只要一题成功就写入历史，低召回或高无依据率将混入网络超时造成的失败，
     * 后续 Prompt 对比会把供应商故障误判为模型质量退化。
     */
    @Test
    void marksTaskPartialFailedWhenAnyEvaluationCaseFails() {
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        AsyncTask task = AsyncTask.queuedRagEvaluation(42L, "test-key");
        task.claim("worker-1", OffsetDateTime.now().plusMinutes(1));
        AsyncTaskRepository tasks = mock(AsyncTaskRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        RagLiveEvaluationRunner runner = mock(RagLiveEvaluationRunner.class);
        RagEvaluationHistoryService history = mock(RagEvaluationHistoryService.class);
        RagEvaluationTaskService taskService = mock(RagEvaluationTaskService.class);
        Workspace workspace = mock(Workspace.class);
        RagEvaluationRun persisted = mock(RagEvaluationRun.class);
        when(tasks.findByPublicId(taskId)).thenReturn(Optional.of(task));
        when(workspaces.findById(42L)).thenReturn(Optional.of(workspace));
        when(workspace.getPublicId()).thenReturn(workspaceId);
        when(history.record(eq(workspaceId), any()))
                .thenReturn(persisted);
        when(persisted.getPublicId()).thenReturn(UUID.randomUUID());
        when(runner.run(workspaceId)).thenReturn(new RagEvaluationRunResult(
                "dataset", "prompt", "model", "retrieval", new RagEvaluationMetrics(1, 1, 0, 2),
                List.of(
                        new RagEvaluationCaseResult("ok", "release-note", "succeeded", 1, 1, 1, 1, false),
                        new RagEvaluationCaseResult("timeout", "known-issue", "failed", 1, 0, 0, 0, true))));

        new RagEvaluationTaskRunner(tasks, workspaces, runner, history, taskService).run(taskId, "worker-1");

        verify(taskService).markPartialFailed(taskId, "worker-1");
        verify(history, never()).record(eq(workspaceId), any());
    }
}
