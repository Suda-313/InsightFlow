package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagEvaluationRun;
import com.insightflow.entity.Workspace;
import com.insightflow.evaluation.rag.RagEvaluationCaseResult;
import com.insightflow.evaluation.rag.RagEvaluationMetrics;
import com.insightflow.evaluation.rag.RagEvaluationRunResult;
import com.insightflow.repository.RagEvaluationRunRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RAG 评测批次必须按 Workspace 持久化，并且只写入脱敏指标与逐题计数，
 * 不能把模型回答或知识原文误写入通用评测历史。
 */
class RagEvaluationHistoryServiceTest {

    @Test
    void recordsRagMetricsInWorkspaceScopedHistory() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = mock(Workspace.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        RagEvaluationRunRepository repository = mock(RagEvaluationRunRepository.class);
        when(workspace.getId()).thenReturn(7L);
        when(workspaces.get(workspaceId)).thenReturn(workspace);
        when(repository.save(any(RagEvaluationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RagEvaluationRunResult result = new RagEvaluationRunResult(
                "rag-gold:v1:abc", "chat:v4", "qwen-test", "knowledge:rrf:v1",
                new RagEvaluationMetrics(1.0, 1.0, 0.0, 2),
                List.of(new RagEvaluationCaseResult("release-note", "release-note", "succeeded", 1, 1, 1, 1, false)));

        RagEvaluationRun persisted = new RagEvaluationHistoryService(workspaces, repository, new ObjectMapper())
                .record(workspaceId, result);

        ArgumentCaptor<RagEvaluationRun> captured = ArgumentCaptor.forClass(RagEvaluationRun.class);
        org.mockito.Mockito.verify(repository).save(captured.capture());
        assertThat(persisted.getWorkspaceId()).isEqualTo(7L);
        assertThat(captured.getValue().getMetricsJson()).contains("retrievalRecallRate");
        assertThat(captured.getValue().getCaseResultsJson()).doesNotContain("模型回答");
    }
}
