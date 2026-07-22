package com.insightflow.agent;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

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
}