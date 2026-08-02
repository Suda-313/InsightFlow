package com.insightflow.agent;

import org.springframework.stereotype.Component;

/**
 * Agent 降级管理器。当 Agent 执行失败时返回默认结果。
 * 当前实现直接返回 null，后续可扩展为规则降级或缓存降级。
 */
@Component
public class AgentFallbackManager {

    /**
     * 返回降级结果。
     *
     * @param agent    失败的 Agent 实例
     * @param input    用户输入
     * @param <T>      输出类型
     * @return 当前始终返回 null
     */
    public <T> T fallback(InsightAgent<T> agent, String input) {
        return null;
    }
}