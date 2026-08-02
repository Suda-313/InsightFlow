package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.common.exception.AgentRunNotFoundException;
import com.insightflow.entity.AgentRun;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AgentRunRepository;
import com.insightflow.service.importing.PiiSanitizer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AgentRun 生命周期的服务层回归测试。
 *
 * <p>测试关注审计记录的工作区隔离和可核验字段，不调用真实模型，也不记录模型原始推理链。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private PiiSanitizer piiSanitizer;

    @Mock
    private Workspace workspace;

    /**
     * 开始记录必须绑定服务器解析出的工作区，并保存脱敏后的输入摘要而不是原始模型 Prompt。
     */
    @Test
    void startsWorkspaceScopedRunWithSanitizedInputSummary() {
        UUID workspacePublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(piiSanitizer.sanitize("玩家邮箱 a@demo.com 无法登录")).thenReturn("玩家邮箱 [EMAIL] 无法登录");
        when(agentRunRepository.save(any(AgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRun run = new AgentRunService(workspaceService, agentRunRepository, piiSanitizer).start(
                workspacePublicId,
                new AgentRunService.StartRequest("chat", "chat:v1", "qwen-test", "none", "玩家邮箱 a@demo.com 无法登录"));

        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(agentRunRepository).save(captor.capture());
        assertThat(run.getPublicId()).isNotNull();
        assertThat(captor.getValue().getWorkspaceId()).isEqualTo(7L);
        assertThat(captor.getValue().getInputSummary()).isEqualTo("玩家邮箱 [EMAIL] 无法登录");
        assertThat(captor.getValue().getStatus()).isEqualTo("running");
    }

    /**
     * 成功完成后必须保存最终回答、模型 Usage 和耗时，供后续评测比较效果与成本。
     */
    @Test
    void completesRunWithFinalOutputUsageAndLatency() {
        UUID workspacePublicId = UUID.randomUUID();
        AgentRun run = AgentRun.start(7L, "chat", "chat:v1", "qwen-test", "none", "已脱敏问题");
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(agentRunRepository.findByPublicIdAndWorkspaceId(run.getPublicId(), 7L)).thenReturn(Optional.of(run));
        when(agentRunRepository.save(run)).thenReturn(run);

        new AgentRunService(workspaceService, agentRunRepository, piiSanitizer).succeed(
                workspacePublicId,
                run.getPublicId(),
                new AgentRunService.Completion("最终回答", null, 11L, 22L, 33L, 456L));

        assertThat(run.getStatus()).isEqualTo("succeeded");
        assertThat(run.getOutputText()).isEqualTo("最终回答");
        assertThat(run.getPromptTokens()).isEqualTo(11);
        assertThat(run.getCompletionTokens()).isEqualTo(22);
        assertThat(run.getTotalTokens()).isEqualTo(33);
        assertThat(run.getLatencyMs()).isEqualTo(456L);
    }

    /**
     * 跨工作区的 Trace 即使真实存在也必须当作不存在，避免运行记录成为越权信息入口。
     */
    @Test
    void rejectsRunOutsideWorkspace() {
        UUID workspacePublicId = UUID.randomUUID();
        UUID runPublicId = UUID.randomUUID();
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(agentRunRepository.findByPublicIdAndWorkspaceId(runPublicId, 7L)).thenReturn(Optional.empty());

        AgentRunService service = new AgentRunService(workspaceService, agentRunRepository, piiSanitizer);

        assertThatThrownBy(() -> service.fail(workspacePublicId, runPublicId, 100L))
                .isInstanceOf(AgentRunNotFoundException.class);
    }

    /**
     * 性能基线必须以同一工作区内已成功完成的调用为样本，并按 Agent、Prompt 和模型分组，
     * 防止不同模板或模型的延迟、Token 被混入同一个百分位数。
     */
    @Test
    void summarizesP50AndP95ByAgentPromptAndModel() {
        UUID workspacePublicId = UUID.randomUUID();
        AgentRun first = successfulRun("chat", "chat:v1", "qwen-test", 100L, 10L, 20L);
        AgentRun second = successfulRun("chat", "chat:v1", "qwen-test", 200L, 20L, 40L);
        AgentRun third = successfulRun("chat", "chat:v1", "qwen-test", 300L, 30L, 60L);
        AgentRun anotherPrompt = successfulRun("chat", "chat:v2", "qwen-test", 400L, 40L, 80L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(7L);
        when(agentRunRepository.findTop100ByWorkspaceIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(first, second, third, anotherPrompt));

        AgentRunService.PerformanceBaseline baseline = new AgentRunService(
                workspaceService, agentRunRepository, piiSanitizer).performanceBaseline(workspacePublicId);

        assertThat(baseline.sampleLimit()).isEqualTo(100);
        assertThat(baseline.metrics()).containsExactlyInAnyOrder(
                new AgentRunService.PerformanceMetric(
                        "chat", "chat:v1", "qwen-test", 3, 200L, 300L, 20L, 30L, 40L, 60L),
                new AgentRunService.PerformanceMetric(
                        "chat", "chat:v2", "qwen-test", 1, 400L, 400L, 40L, 40L, 80L, 80L));
    }

    /** 构造已完成记录只服务于基线聚合测试，生产调用仍必须通过 AgentRunService 生命周期更新。 */
    private AgentRun successfulRun(
            String agentType, String promptVersion, String modelName, long latencyMs, long promptTokens, long completionTokens) {
        AgentRun run = AgentRun.start(7L, agentType, promptVersion, modelName, "none", "已脱敏输入");
        run.succeed("最终回答", null, promptTokens, completionTokens, promptTokens + completionTokens, latencyMs);
        return run;
    }
}
