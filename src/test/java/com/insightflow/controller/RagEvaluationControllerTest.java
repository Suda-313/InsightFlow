package com.insightflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AsyncTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.evaluation.GoldEvaluationRunner;
import com.insightflow.evaluation.rag.RagEvaluationTaskCommandService;
import com.insightflow.evaluation.rag.RagEvaluationTaskQueryService;
import com.insightflow.service.EvaluationHistoryService;
import com.insightflow.service.RagEvaluationHistoryService;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * RAG 专项接口只公开批次 UUID 与脱敏指标，不把内部 Workspace 键、知识原文
 * 或模型回答返回给浏览器；历史读取也委托给带 Workspace 约束的服务。
 */
class RagEvaluationControllerTest {

    /**
     * 全部题目失败时 result_json 只保存失败摘要，不是历史评测 UUID；
     * 若误映射为 run_id，前端会把 JSON 片段当作可跳转的评测批次。
     */
    @Test
    void omitsRunIdForPartialFailedTask() {
        AsyncTask task = AsyncTask.queuedRagEvaluation(42L, "test-key");
        task.claim("worker-1", OffsetDateTime.now().plusMinutes(1));
        task.markPartialFailed("{\"reason\":\"NO_EVALUABLE_CASE\"}");

        EvaluationController.RagTaskStatusResponse response = EvaluationController.RagTaskStatusResponse.from(task);

        assertThat(response.status()).isEqualTo("partial_failed");
        assertThat(response.runId()).isNull();
    }

    @Test
    void acceptsRagEvaluationAsAsyncTask() {
        UUID workspaceId = UUID.randomUUID();
        GoldEvaluationRunner goldRunner = mock(GoldEvaluationRunner.class);
        EvaluationHistoryService goldHistory = mock(EvaluationHistoryService.class);
        RagEvaluationHistoryService ragHistory = mock(RagEvaluationHistoryService.class);
        RagEvaluationTaskCommandService commandService = mock(RagEvaluationTaskCommandService.class);
        RagEvaluationTaskQueryService queryService = mock(RagEvaluationTaskQueryService.class);
        AsyncTask task = mock(AsyncTask.class);
        UUID taskId = UUID.randomUUID();
        when(task.getPublicId()).thenReturn(taskId);
        when(task.getStatus()).thenReturn("queued");
        when(commandService.enqueue(workspaceId)).thenReturn(task);

        EvaluationController controller = new EvaluationController(
                goldRunner, goldHistory, ragHistory, commandService, queryService, new ObjectMapper());
        EvaluationController.RagTaskResponse response = controller.runRagEvaluation(workspaceId);

        assertThat(response.taskId()).isEqualTo(taskId);
        assertThat(response.status()).isEqualTo("queued");
    }
}
