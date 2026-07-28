package com.insightflow.evaluation.rag;

import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeSearchTool;
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
 * RAG 评测单题的时间边界。
 *
 * <p>检索和回答仍复用线上受控 Tool 与网关；此类只负责将一次题目调用放入有界线程池，
 * 在超时、拒绝或运行异常时返回脱敏失败结果，使整批评测能继续收敛。</p>
 */
@Component
public class RagEvaluationCaseExecutor {

    /** 线上检索边界，内部继续完成 Workspace 可见范围和 RRF 检索约束。*/
    private final KnowledgeSearchTool knowledgeSearchTool;

    /** 回答网关只接收本题问题和受控检索结果，不能绕过 Tool 访问业务数据。*/
    private final RagEvaluationAnswerGateway answerGateway;

    /** 独立的小型调用池与任务 Worker 分离，防止卡住的供应商请求占满调度线程。*/
    private final AsyncTaskExecutor callExecutor;

    /** 单题总时间上限；底层 HTTP 读超时会在更早时刻真正中断网络等待。*/
    private final long caseTimeoutMillis;

    /**
     * 通过命名线程池隔离高延迟模型调用，避免和 CSV、投影、报告任务争用同一执行资源。
     */
    @Autowired
    public RagEvaluationCaseExecutor(
            KnowledgeSearchTool knowledgeSearchTool,
            RagEvaluationAnswerGateway answerGateway,
            @Qualifier("ragEvaluationCallExecutor") AsyncTaskExecutor callExecutor,
            @Value("${insightflow.evaluation.rag.case-timeout-seconds:120}") long caseTimeoutSeconds) {
        this(knowledgeSearchTool, answerGateway, callExecutor, Math.toIntExact(caseTimeoutSeconds * 1000L));
    }

    /**
     * 毫秒构造器只为确定性的超时测试保留；生产注入仍统一使用秒级配置，避免 API 接收细粒度超时参数。
     */
    RagEvaluationCaseExecutor(
            KnowledgeSearchTool knowledgeSearchTool,
            RagEvaluationAnswerGateway answerGateway,
            AsyncTaskExecutor callExecutor,
            int caseTimeoutMillis) {
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.answerGateway = answerGateway;
        this.callExecutor = callExecutor;
        this.caseTimeoutMillis = caseTimeoutMillis;
    }

    /**
     * 执行一题并把异常收敛为固定失败阶段。
     *
     * <p>取消 Future 只是业务层的第二道保险；网络客户端另有读超时，
     * 两者共同避免中断不被供应商 SDK 立即响应时长期占用调用线程。</p>
     */
    public RagEvaluationCaseExecution execute(UUID workspacePublicId, RagEvaluationCaseDefinition evaluationCase) {
        long startedAt = System.nanoTime();
        AtomicReference<String> stage = new AtomicReference<>("retrieval");
        // 生成阶段异常会跨 Future 边界，必须保存已完成的检索耗时，不能仅凭最终阶段名称猜测归因。
        AtomicReference<Long> retrievalLatencyMs = new AtomicReference<>();
        Future<RagEvaluationCaseExecution> future = callExecutor.submit(
                () -> executeCall(workspacePublicId, evaluationCase, stage, retrievalLatencyMs, startedAt));
        try {
            return future.get(caseTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            long totalLatencyMs = elapsedMillis(startedAt);
            return failedAt(stage.get(), retrievalLatencyMs.get(), totalLatencyMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            long totalLatencyMs = elapsedMillis(startedAt);
            return failedAt("interrupted", retrievalLatencyMs.get(), totalLatencyMs);
        } catch (ExecutionException | RuntimeException exception) {
            long totalLatencyMs = elapsedMillis(startedAt);
            return failedAt(stage.get() + "_failed", retrievalLatencyMs.get(), totalLatencyMs);
        }
    }

    /** 真实调用在独立线程运行；阶段名仅用于受控日志和错误码，不包含异常消息或请求内容。*/
    private RagEvaluationCaseExecution executeCall(
            UUID workspacePublicId,
            RagEvaluationCaseDefinition evaluationCase,
            AtomicReference<String> stage,
            AtomicReference<Long> retrievalLatencyMs,
            long startedAt) {
        long retrievalStartedAt = System.nanoTime();
        KnowledgeRetrievalResult retrieval = knowledgeSearchTool.retrieve(workspacePublicId, evaluationCase.question());
        long retrievalLatency = elapsedMillis(retrievalStartedAt);
        retrievalLatencyMs.set(retrievalLatency);
        stage.set("generation");
        long generationStartedAt = System.nanoTime();
        String answer = answerGateway.answer(evaluationCase.question(), retrieval);
        long generationLatencyMs = elapsedMillis(generationStartedAt);
        return new RagEvaluationCaseExecution(
                retrieval, answer == null ? "" : answer, "succeeded", null,
                retrievalLatency, generationLatencyMs, elapsedMillis(startedAt));
    }

    /** 将阶段转为固定错误码；生成阶段前的超时只报告检索耗时，不伪造生成指标。*/
    private RagEvaluationCaseExecution failedAt(String stage, Long retrievalLatencyMs, long totalLatencyMs) {
        boolean generationStarted = stage.startsWith("generation");
        long measuredRetrievalLatency = retrievalLatencyMs == null ? totalLatencyMs : retrievalLatencyMs;
        long measuredGenerationLatency = generationStarted
                ? Math.max(0L, totalLatencyMs - measuredRetrievalLatency)
                : 0L;
        return RagEvaluationCaseExecution.failed(
                stage.endsWith("_failed") || "interrupted".equals(stage) ? stage : stage + "_timeout",
                measuredRetrievalLatency,
                measuredGenerationLatency,
                totalLatencyMs);
    }

    /** 纳秒计时只在进程内计算耗时，不向 API 或日志暴露系统时钟细节。*/
    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
