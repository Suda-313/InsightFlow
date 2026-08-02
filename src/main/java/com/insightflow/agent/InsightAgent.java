package com.insightflow.agent;

import java.util.List;

/**
 * Tool 回调接口，等价于 Spring AI 的 ToolCallback。
 * 当真实 Spring AI 依赖可用后，可替换为 {@code org.springframework.ai.tool.ToolCallback}。
 */
@FunctionalInterface
interface ToolCallback {
    String getName();
}

/**
 * 原子 Agent 接口：一个 Agent 封装一个 LLM 调用，包含 system prompt、工具定义、输出 schema 和执行入口。
 */
public interface InsightAgent<T> {

    String systemPrompt();

    /**
     * 返回当前系统提示词的可追踪版本；未迁移的实现保留受控默认值，避免接口升级影响非 LLM 适配器。
     */
    default String promptVersion() {
        return "unversioned";
    }

    default List<ToolCallback> tools() {
        return List.of();
    }

    Class<T> outputSchema();

    T execute(String userInput);
}
