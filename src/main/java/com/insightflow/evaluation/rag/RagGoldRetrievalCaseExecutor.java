package com.insightflow.evaluation.rag;

import com.insightflow.knowledge.KnowledgeEmbeddingGateway;
import com.insightflow.knowledge.KnowledgeRetrievalDiagnostics;
import com.insightflow.knowledge.KnowledgeRetrievalOptions;
import com.insightflow.knowledge.KnowledgeSearchTool;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 金标 retrieval-only 单题执行器：只走受控检索，不调用回答网关。
 *
 * <p>支持评测专用 embedding 缓存；CROSS/VERSION 题使用金标 requirement 组对齐的子查询分解。</p>
 */
@Component
public class RagGoldRetrievalCaseExecutor {

    private final KnowledgeSearchTool knowledgeSearchTool;
    private final KnowledgeEmbeddingGateway embeddingGateway;
    private final RagGoldCrossQueryDecomposer goldCrossQueryDecomposer;
    private final AsyncTaskExecutor callExecutor;
    private final long caseTimeoutMillis;

    @Autowired
    public RagGoldRetrievalCaseExecutor(
            KnowledgeSearchTool knowledgeSearchTool,
            KnowledgeEmbeddingGateway embeddingGateway,
            RagGoldCrossQueryDecomposer goldCrossQueryDecomposer,
            @Qualifier("ragEvaluationCallExecutor") AsyncTaskExecutor callExecutor,
            @Value("${insightflow.evaluation.rag.case-timeout-seconds:120}") long caseTimeoutSeconds) {
        this(
                knowledgeSearchTool,
                embeddingGateway,
                goldCrossQueryDecomposer,
                callExecutor,
                Math.toIntExact(caseTimeoutSeconds * 1000L));
    }

    RagGoldRetrievalCaseExecutor(
            KnowledgeSearchTool knowledgeSearchTool,
            KnowledgeEmbeddingGateway embeddingGateway,
            RagGoldCrossQueryDecomposer goldCrossQueryDecomposer,
            AsyncTaskExecutor callExecutor,
            int caseTimeoutMillis) {
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.embeddingGateway = embeddingGateway;
        this.goldCrossQueryDecomposer = goldCrossQueryDecomposer;
        this.callExecutor = callExecutor;
        this.caseTimeoutMillis = caseTimeoutMillis;
    }

    public RagGoldRetrievalCaseExecution execute(
            UUID workspacePublicId,
            RagEvaluationCaseDefinition evaluationCase,
            RagGoldRetrievalExecutionContext context) {
        long startedAt = System.nanoTime();
        Future<RagGoldRetrievalCaseExecution> future = callExecutor.submit(
                () -> executeCall(workspacePublicId, evaluationCase, context, startedAt));
        try {
            return future.get(caseTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return RagGoldRetrievalCaseExecution.failed("retrieval_timeout", elapsedMillis(startedAt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return RagGoldRetrievalCaseExecution.failed("interrupted", elapsedMillis(startedAt));
        } catch (ExecutionException | RuntimeException exception) {
            return RagGoldRetrievalCaseExecution.failed("retrieval_failed", elapsedMillis(startedAt));
        }
    }

    private RagGoldRetrievalCaseExecution executeCall(
            UUID workspacePublicId,
            RagEvaluationCaseDefinition evaluationCase,
            RagGoldRetrievalExecutionContext context,
            long startedAt) {
        long retrievalStartedAt = System.nanoTime();
        List<Double> embedding = resolveEmbedding(evaluationCase.question(), context);
        List<String> subQueries = goldCrossQueryDecomposer.buildSubQueries(
                evaluationCase.question(),
                context.questionType(),
                context.evidences());
        KnowledgeRetrievalOptions retrievalOptions = KnowledgeRetrievalOptions.withDecomposition(
                context.rerankerEnabled(),
                subQueries,
                context.questionType() == null ? null : context.questionType().name());
        KnowledgeRetrievalDiagnostics diagnostics = knowledgeSearchTool.retrieveWithDiagnostics(
                workspacePublicId,
                evaluationCase.question(),
                embedding,
                retrievalOptions);
        long retrievalLatencyMs = elapsedMillis(retrievalStartedAt);
        return RagGoldRetrievalCaseExecution.succeeded(diagnostics, retrievalLatencyMs, elapsedMillis(startedAt));
    }

    private List<Double> resolveEmbedding(String question, RagGoldRetrievalExecutionContext context) {
        if (context.useEmbeddingCache() && context.embeddingCache() != null) {
            var cached = context.embeddingCache().get(context.datasetChecksum(), context.embeddingModel(), question);
            if (cached.isPresent()) {
                return cached.get();
            }
            List<Double> embedding = embeddingGateway.embed(List.of(question)).get(0);
            try {
                context.embeddingCache().put(context.datasetChecksum(), context.embeddingModel(), question, embedding);
            } catch (Exception exception) {
                // 缓存写入失败不阻断评测，只损失复用能力。
            }
            return embedding;
        }
        return embeddingGateway.embed(List.of(question)).get(0);
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
