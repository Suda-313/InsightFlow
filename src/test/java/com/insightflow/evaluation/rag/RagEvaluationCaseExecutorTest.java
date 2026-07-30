package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.knowledge.KnowledgeRetrievalDiagnostics;
import com.insightflow.knowledge.KnowledgeRetrievalOptions;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeSearchTool;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;

/**
 * 单题超时必须独立收敛，不能因供应商调用阻塞让整批 RAG 评测永久卡在 running。
 */
class RagEvaluationCaseExecutorTest {

    @Test
    void marksTimedOutRetrievalAsFailedWithoutLeakingItsException() {
        UUID workspaceId = UUID.randomUUID();
        RagEvaluationCaseDefinition slowCase = new RagEvaluationCaseDefinition(
                "slow-retrieval", "release-note", "慢检索题", Set.of("knowledge:release:"));
        KnowledgeSearchTool searchTool = mock(KnowledgeSearchTool.class);
        RagEvaluationAnswerGateway answerGateway = mock(RagEvaluationAnswerGateway.class);
        when(searchTool.retrieveWithDiagnostics(any(), any(), any(), any())).thenAnswer(ignored -> {
            Thread.sleep(200);
            return null;
        });
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            RagEvaluationCaseExecutor executor = new RagEvaluationCaseExecutor(
                    searchTool, answerGateway, new ConcurrentTaskExecutor(delegate), 10);

            RagEvaluationCaseExecution result = executor.execute(workspaceId, slowCase);

            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.failureStage()).isEqualTo("retrieval_timeout");
            assertThat(result.retrievalLatencyMs()).isGreaterThanOrEqualTo(0L);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void passesRerankerFlagToRetrievalDiagnostics() {
        UUID workspaceId = UUID.randomUUID();
        RagEvaluationCaseDefinition evaluationCase = new RagEvaluationCaseDefinition(
                "rerank-case", "release-note", "精排题", Set.of("knowledge:release:"));
        KnowledgeSearchTool searchTool = mock(KnowledgeSearchTool.class);
        RagEvaluationAnswerGateway answerGateway = mock(RagEvaluationAnswerGateway.class);
        KnowledgeRetrievalResult retrieval = new KnowledgeRetrievalResult(1, List.of());
        KnowledgeRetrievalDiagnostics diagnostics = new KnowledgeRetrievalDiagnostics(
                retrieval, List.of(), Set.of(), Set.of(), Set.of());
        when(searchTool.retrieveWithDiagnostics(
                        eq(workspaceId), eq("精排题"), eq(null), eq(KnowledgeRetrievalOptions.withReranker(true))))
                .thenReturn(diagnostics);
        when(answerGateway.answer(any(), any())).thenReturn("answer");
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            RagEvaluationCaseExecution result = new RagEvaluationCaseExecutor(
                    searchTool, answerGateway, new ConcurrentTaskExecutor(delegate), 500)
                    .execute(workspaceId, evaluationCase, true);

            assertThat(result.status()).isEqualTo("succeeded");
            assertThat(result.retrievalDiagnostics()).isSameAs(diagnostics);
            verify(searchTool).retrieveWithDiagnostics(
                    eq(workspaceId), eq("精排题"), eq(null), eq(KnowledgeRetrievalOptions.withReranker(true)));
        } finally {
            delegate.shutdownNow();
        }
    }

    /**
     * 供应商在生成阶段返回异常时，日志必须把耗时归入 generation；
     * 否则运营排查会把模型或网络故障错误归因为知识库检索变慢。
     */
    @Test
    void attributesGenerationFailureLatencyToGenerationStage() {
        UUID workspaceId = UUID.randomUUID();
        RagEvaluationCaseDefinition evaluationCase = new RagEvaluationCaseDefinition(
                "generation-failure", "release-note", "生成失败题", Set.of("knowledge:release:"));
        KnowledgeSearchTool searchTool = mock(KnowledgeSearchTool.class);
        RagEvaluationAnswerGateway answerGateway = mock(RagEvaluationAnswerGateway.class);
        KnowledgeRetrievalResult retrieval = new KnowledgeRetrievalResult(0, List.of());
        KnowledgeRetrievalDiagnostics diagnostics = new KnowledgeRetrievalDiagnostics(
                retrieval, List.of(), Set.of(), Set.of(), Set.of());
        when(searchTool.retrieveWithDiagnostics(any(), any(), any(), any())).thenReturn(diagnostics);
        when(answerGateway.answer(any(), any())).thenAnswer(ignored -> {
            Thread.sleep(20);
            throw new IllegalStateException("provider unavailable");
        });
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            RagEvaluationCaseExecution result = new RagEvaluationCaseExecutor(
                    searchTool, answerGateway, new ConcurrentTaskExecutor(delegate), 500).execute(workspaceId, evaluationCase);

            assertThat(result.failureStage()).isEqualTo("generation_failed");
            assertThat(result.generationLatencyMs()).isGreaterThan(0L);
            assertThat(result.retrievalLatencyMs()).isLessThan(result.totalLatencyMs());
        } finally {
            delegate.shutdownNow();
        }
    }
}
