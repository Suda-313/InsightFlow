package com.insightflow.evaluation.rag;

import com.insightflow.agent.investigation.ChatSessionFocus;
import com.insightflow.agent.investigation.ContextualQueryRewriter;
import com.insightflow.agent.investigation.ConversationFocusExtractor;
import com.insightflow.agent.investigation.RewriteOutcome;
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
    private final ContextualQueryRewriter contextualQueryRewriter;
    private final ConversationFocusExtractor focusExtractor;
    private final AsyncTaskExecutor callExecutor;
    private final long caseTimeoutMillis;

    @Autowired
    public RagGoldRetrievalCaseExecutor(
            KnowledgeSearchTool knowledgeSearchTool,
            KnowledgeEmbeddingGateway embeddingGateway,
            RagGoldCrossQueryDecomposer goldCrossQueryDecomposer,
            ContextualQueryRewriter contextualQueryRewriter,
            ConversationFocusExtractor focusExtractor,
            @Qualifier("ragEvaluationCallExecutor") AsyncTaskExecutor callExecutor,
            @Value("${insightflow.evaluation.rag.case-timeout-seconds:120}") long caseTimeoutSeconds) {
        this(
                knowledgeSearchTool,
                embeddingGateway,
                goldCrossQueryDecomposer,
                contextualQueryRewriter,
                focusExtractor,
                callExecutor,
                Math.toIntExact(caseTimeoutSeconds * 1000L));
    }

    RagGoldRetrievalCaseExecutor(
            KnowledgeSearchTool knowledgeSearchTool,
            KnowledgeEmbeddingGateway embeddingGateway,
            RagGoldCrossQueryDecomposer goldCrossQueryDecomposer,
            ContextualQueryRewriter contextualQueryRewriter,
            ConversationFocusExtractor focusExtractor,
            AsyncTaskExecutor callExecutor,
            int caseTimeoutMillis) {
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.embeddingGateway = embeddingGateway;
        this.goldCrossQueryDecomposer = goldCrossQueryDecomposer;
        this.contextualQueryRewriter = contextualQueryRewriter;
        this.focusExtractor = focusExtractor;
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
        String retrievalQuery = resolveRetrievalQuery(evaluationCase);
        List<Double> embedding = resolveEmbedding(retrievalQuery, context);
        List<String> subQueries = goldCrossQueryDecomposer.buildSubQueries(
                retrievalQuery,
                context.questionType(),
                context.evidences());
        KnowledgeRetrievalOptions retrievalOptions = KnowledgeRetrievalOptions.withDecomposition(
                context.rerankerEnabled(),
                subQueries,
                context.questionType() == null ? null : context.questionType().name(),
                context.identifierSupplementEnabled(),
                context.subQueryQuotaEnabled(),
                context.evidenceGateEnabled());
        KnowledgeRetrievalDiagnostics diagnostics = knowledgeSearchTool.retrieveWithDiagnostics(
                workspacePublicId,
                retrievalQuery,
                embedding,
                retrievalOptions);
        long retrievalLatencyMs = elapsedMillis(retrievalStartedAt);
        return RagGoldRetrievalCaseExecution.succeeded(diagnostics, retrievalLatencyMs, elapsedMillis(startedAt));
    }

    /** 多轮题先用 context turns 抽焦点再规则改写；单轮题保持原 question 引用不变。 */
    private String resolveRetrievalQuery(RagEvaluationCaseDefinition evaluationCase) {
        if (evaluationCase.contextTurns().isEmpty()) {
            return evaluationCase.question();
        }
        ChatSessionFocus focus = focusExtractor.extractFromText(evaluationCase.contextTurns());
        RewriteOutcome outcome = contextualQueryRewriter.rewrite(evaluationCase.question(), focus);
        return outcome.rewritten();
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
