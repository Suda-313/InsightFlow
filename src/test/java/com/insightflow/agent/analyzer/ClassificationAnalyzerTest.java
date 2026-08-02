package com.insightflow.agent.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.dto.ClassificationResult;
import com.insightflow.entity.AgentRun;
import com.insightflow.prompt.LiteralChatModelCaller;
import com.insightflow.prompt.OperationalPromptCatalog;
import com.insightflow.service.AgentRunService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 验证分类 Agent 按公开 JSON 契约解析模型输出，而不是依赖 Java 字段名。 */
@ExtendWith(OutputCaptureExtension.class)
class ClassificationAnalyzerTest {

    private final LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
    private final AgentRunService agentRunService = mock(AgentRunService.class);
    private final ClassificationAnalyzer analyzer = new ClassificationAnalyzer(
            literalChatModelCaller, new ObjectMapper(), new OperationalPromptCatalog(), agentRunService, "qwen-test");

    @Test
    void executeParsesSnakeCaseClassificationContractAndLogsModelLifecycle(CapturedOutput output) {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = mock(AssistantMessage.class);

        // 模型输出必须使用 canonical_key，避免测试掩盖生产 Prompt 与 DTO 的协议偏差。
        when(assistantMessage.getContent()).thenReturn(
                "{\"canonical_key\":\"login_failure\",\"confidence\":0.9,"
                        + "\"reasoning\":\"login failed\",\"keywords\":[\"login\",\"failure\"]}");
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(chatResponse.getResult()).thenReturn(generation);
        when(chatResponse.getMetadata()).thenReturn(null);
        when(literalChatModelCaller.call(anyString(), anyString())).thenReturn(chatResponse);

        UUID workspaceId = UUID.randomUUID();
        AgentRun run = AgentRun.start(7L, "classification", "classification:v1", "qwen-test", "none", "input");
        when(agentRunService.start(org.mockito.ArgumentMatchers.eq(workspaceId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(run);

        ClassificationResult result = analyzer.execute(workspaceId, "cannot log in");

        assertThat(result).isNotNull();
        assertThat(result.canonicalKey()).isEqualTo("login_failure");
        assertThat(analyzer.promptVersion()).isEqualTo("classification:v1");
        verify(agentRunService).succeed(org.mockito.ArgumentMatchers.eq(workspaceId),
                org.mockito.ArgumentMatchers.eq(run.getPublicId()), org.mockito.ArgumentMatchers.any());
        assertThat(output).contains("LLM[Classification]")
                .contains("status=started")
                .contains("prompt_version=classification:v1")
                .contains("input_chars=13")
                .contains("status=succeeded");
    }
}
