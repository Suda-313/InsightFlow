package com.insightflow.agent.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.agent.dto.ClassificationResult;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * ClassificationAnalyzer 单元测试。
 */
class ClassificationAnalyzerTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

    private final ClassificationAnalyzer analyzer = new ClassificationAnalyzer(chatClient);

    @Test
    void executeInvokesChatClientAndReturnsResult() {
        ClassificationResult expected = new ClassificationResult(
                "login_failure", 0.9, "用户无法登录", List.of("登录", "失败", "账号"));

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(ClassificationResult.class)).thenReturn(expected);

        ClassificationResult result = analyzer.execute("我登不上游戏了");

        assertThat(result).isEqualTo(expected);
        verify(chatClient).prompt();
        verify(requestSpec).system(analyzer.systemPrompt());
        verify(requestSpec).user("我登不上游戏了");
        verify(requestSpec).call();
        verify(callResponseSpec).entity(ClassificationResult.class);
    }
}
