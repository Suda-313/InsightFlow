package com.insightflow.agent;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Agent 编排器。并行执行一组 InsightAgent，单个 Agent 失败时由
 * AgentFallbackManager 降级，不影响其他 Agent 的执行结果。
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final AgentFallbackManager fallbackManager;
    private final int timeoutSeconds;

    public AgentOrchestrator(AgentFallbackManager fallbackManager,
                             @Value("${insightflow.agent.timeout-seconds:30}") int timeoutSeconds) {
        this.fallbackManager = fallbackManager;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 并行执行一组 Agent，返回与入参顺序一致的结果列表。
     * 执行失败的 Agent 其对应位置为 null。
     * 内部使用 CompletableFuture 并行执行，调用方已异步则无需额外 @Async。
     */
    public <T> List<T> parallel(List<? extends InsightAgent<? extends T>> agents, String userInput) {
        List<CompletableFuture<T>> futures = agents.stream()
                .map(agent -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return agent.execute(userInput);
                    } catch (Exception e) {
                        log.warn("Agent [{}] failed, falling back: {}", agent.getClass().getSimpleName(), e.getMessage());
                        return fallbackManager.fallback(agent, userInput);
                    }
                }))
                .toList();

        List<T> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get(timeoutSeconds, SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Agent at index {} was interrupted", i);
                results.add(null);
            } catch (ExecutionException | TimeoutException e) {
                log.warn("Agent at index {} failed or timed out: {}", i, e.getMessage());
                results.add(null);
            }
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * 并行执行已绑定工作区上下文的任务；调用方在 Supplier 内负责 AgentRun 生命周期，
     * 因此不使用无上下文的 fallback 重新发起一次不可审计调用。
     */
    public <T> List<T> parallelTasks(List<? extends Supplier<? extends T>> tasks) {
        List<CompletableFuture<T>> futures = tasks.stream()
                .map(task -> CompletableFuture.<T>supplyAsync(() -> {
                    try {
                        return task.get();
                    } catch (RuntimeException exception) {
                        log.warn("Agent task failed with exception_type={}", exception.getClass().getSimpleName());
                        return null;
                    }
                }))
                .toList();
        List<T> results = new ArrayList<>(futures.size());
        for (int index = 0; index < futures.size(); index++) {
            try {
                results.add(futures.get(index).get(timeoutSeconds, SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                results.add(null);
            } catch (ExecutionException | TimeoutException exception) {
                results.add(null);
            }
        }
        return Collections.unmodifiableList(results);
    }
}
