package com.insightflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AgentRun;
import com.insightflow.service.AgentRunService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AgentRun 只读 HTTP 契约测试。
 *
 * <p>Controller 只转换公开 Trace 和可审计字段，工作区归属校验由 AgentRunService 统一负责。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentRunControllerTest {

    @Mock
    private AgentRunService agentRunService;

    /**
     * 运行列表不暴露内部 id，但应提供评测筛选需要的 Agent、状态、模型、耗时与错误码。
     */
    @Test
    void listsRecentRunsUsingPublicTrace() {
        UUID workspaceId = UUID.randomUUID();
        AgentRun run = AgentRun.start(7L, "chat", "chat:v1", "qwen-test", "none", "已脱敏问题");
        run.succeed("最终回答", null, 10L, 20L, 30L, 100L);
        when(agentRunService.listRecent(workspaceId)).thenReturn(List.of(run));

        List<AgentRunController.RunSummaryResponse> response = new AgentRunController(agentRunService)
                .list(workspaceId);

        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.traceId()).isEqualTo(run.getPublicId());
            assertThat(item.agentType()).isEqualTo("chat");
            assertThat(item.status()).isEqualTo("succeeded");
            assertThat(item.modelName()).isEqualTo("qwen-test");
            assertThat(item.latencyMs()).isEqualTo(100L);
        });
    }

    /**
     * 单条详情包含脱敏输入、最终输出和 Usage，但不包含数据库内部 id 或模型思维链。
     */
    @Test
    void returnsRunDetailForPublicTrace() {
        UUID workspaceId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        AgentRun run = AgentRun.start(7L, "chat", "chat:v1", "qwen-test", "none", "已脱敏问题");
        run.succeed("最终回答", null, 10L, 20L, 30L, 100L);
        when(agentRunService.get(workspaceId, traceId)).thenReturn(run);

        AgentRunController.RunDetailResponse response = new AgentRunController(agentRunService)
                .get(workspaceId, traceId);

        assertThat(response.traceId()).isEqualTo(run.getPublicId());
        assertThat(response.inputSummary()).isEqualTo("已脱敏问题");
        assertThat(response.outputText()).isEqualTo("最终回答");
        assertThat(response.totalTokens()).isEqualTo(30L);
    }

    /** 性能端点只返回当前工作区的聚合百分位，不混入单条输入或输出正文。 */
    @Test
    void returnsWorkspaceScopedPerformanceBaseline() {
        UUID workspaceId = UUID.randomUUID();
        AgentRunService.PerformanceBaseline baseline = new AgentRunService.PerformanceBaseline(
                100,
                List.of(new AgentRunService.PerformanceMetric(
                        "chat", "chat:v1", "qwen-test", 2, 120L, 220L, 30L, 40L, 60L, 80L)));
        when(agentRunService.performanceBaseline(workspaceId)).thenReturn(baseline);

        AgentRunService.PerformanceBaseline response = new AgentRunController(agentRunService)
                .performanceBaseline(workspaceId);

        assertThat(response).isEqualTo(baseline);
    }
}
