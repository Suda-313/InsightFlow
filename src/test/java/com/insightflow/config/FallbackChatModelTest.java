package com.insightflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

class FallbackChatModelTest {

    private final Prompt prompt = new Prompt("question");
    private final ChatResponse response = mock(ChatResponse.class);

    @Test
    void usesPrimaryModelWhenCallSucceeds() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        when(primary.call(prompt)).thenReturn(response);

        FallbackChatModel model = new FallbackChatModel(primary, fallback, "primary", "fallback");

        assertThat(model.call(prompt)).isSameAs(response);
        verify(primary).call(prompt);
    }

    @Test
    void switchesToFallbackOnQuotaFailure() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        when(primary.call(prompt)).thenThrow(new RuntimeException("insufficient quota for model"));
        when(fallback.call(prompt)).thenReturn(response);

        FallbackChatModel model = new FallbackChatModel(primary, fallback, "primary", "fallback");

        assertThat(model.call(prompt)).isSameAs(response);
        verify(fallback).call(prompt);
    }

    @Test
    void rethrowsNonQuotaFailuresWithoutTryingFallback() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        RuntimeException invalidRequest = new RuntimeException("invalid api key");
        when(primary.call(prompt)).thenThrow(invalidRequest);

        FallbackChatModel model = new FallbackChatModel(primary, fallback, "primary", "fallback");

        assertThatThrownBy(() -> model.call(prompt)).isSameAs(invalidRequest);
    }

    @Test
    void detectorRecognizesHttp429() {
        HttpClientErrorException tooManyRequests =
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "rate limit", null, null, null);
        assertThat(ChatModelQuotaFailureDetector.shouldTryFallback(tooManyRequests)).isTrue();
    }
}
