package com.insightflow.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * AgentOrchestrator 并行执行与降级验证。
 */
class AgentOrchestratorTest {

    private final AgentFallbackManager fallbackManager = new AgentFallbackManager();
    private final AgentOrchestrator orchestrator = new AgentOrchestrator(fallbackManager, 30);

    /** 两个 Agent 均成功执行，返回完整结果列表。 */
    @Test
    void parallelExecutesAllAgentsAndReturnsResults() {
        InsightAgent<String> agent1 = new TestAgent("alpha", String.class);
        InsightAgent<String> agent2 = new TestAgent("beta", String.class);

        List<String> results = orchestrator.parallel(List.of(agent1, agent2), "input");

        assertThat(results).containsExactly("alpha", "beta");
    }

    /** 其中一个 Agent 失败时，对应位置为 null。 */
    @Test
    void parallelReturnsNullForFailedAgent() {
        InsightAgent<String> agent1 = new TestAgent("ok", String.class);
        InsightAgent<String> agent2 = new FailingAgent(String.class);

        List<String> results = orchestrator.parallel(List.of(agent1, agent2), "input");

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo("ok");
        assertThat(results.get(1)).isNull();
    }

    // -- 测试辅助实现 -------------------------------------------------------

    private record TestAgent(String result, Class<String> schema) implements InsightAgent<String> {
        @Override
        public String systemPrompt() {
            return "test prompt";
        }

        @Override
        public Class<String> outputSchema() {
            return schema;
        }

        @Override
        public String execute(String userInput) {
            return result;
        }
    }

    private record FailingAgent(Class<String> schema) implements InsightAgent<String> {
        @Override
        public String systemPrompt() {
            return "failing prompt";
        }

        @Override
        public Class<String> outputSchema() {
            return schema;
        }

        @Override
        public String execute(String userInput) {
            throw new RuntimeException("simulated agent failure");
        }
    }
}