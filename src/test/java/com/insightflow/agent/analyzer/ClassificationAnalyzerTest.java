package com.insightflow.agent.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.dto.ClassificationResult;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ClassificationAnalyzerTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ClassificationAnalyzer analyzer = new ClassificationAnalyzer(chatClient, objectMapper);

    @Test
    void executeInvokesChatClientAndReturnsResult() throws Exception {
        String json = "{\"canonicalKey\":\"login_failure\",\"confidence\":0.9,\"reasoning\":\"用户无法登录\",\"keywords\":[\"登录\",\"失败\",\"账号\"]}";
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = mock(AssistantMessage.class);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(assistantMessage.getContent()).thenReturn(json);
        when(chatResponse.getMetadata()).thenReturn(null);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse);

        ClassificationResult result = analyzer.execute("我登不上游戏了");

        assertThat(result).isNotNull();
        assertThat(result.canonicalKey()).isEqualTo("login_failure");
    }
}
