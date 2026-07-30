package com.insightflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 主模型额度或限流失败时，自动切换到备用模型的 {@link ChatModel} 装饰器。
 *
 * <p>备用模型与主模型共享同一 DashScope 密钥和 HTTP 超时；仅模型名不同。
 * 评测与聊天共用该 Bean，避免在 dev-240 长跑中因主模型额度耗尽整批失败。</p>
 */
final class FallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

    private final ChatModel primaryModel;
    private final ChatModel fallbackModel;
    private final String primaryModelName;
    private final String fallbackModelName;

    FallbackChatModel(
            ChatModel primaryModel,
            ChatModel fallbackModel,
            String primaryModelName,
            String fallbackModelName) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel;
        this.primaryModelName = primaryModelName;
        this.fallbackModelName = fallbackModelName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return primaryModel.call(prompt);
        } catch (RuntimeException primaryFailure) {
            if (!ChatModelQuotaFailureDetector.shouldTryFallback(primaryFailure)) {
                throw primaryFailure;
            }
            log.warn(
                    "LLM[Chat] primary_model_failed model={}, fallback_model={}, reason={}",
                    primaryModelName,
                    fallbackModelName,
                    primaryFailure.getMessage());
            try {
                ChatResponse response = fallbackModel.call(prompt);
                log.info("LLM[Chat] fallback_model_succeeded model={}", fallbackModelName);
                return response;
            } catch (RuntimeException fallbackFailure) {
                primaryFailure.addSuppressed(fallbackFailure);
                throw primaryFailure;
            }
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return primaryModel.stream(prompt).onErrorResume(primaryFailure -> {
            if (!ChatModelQuotaFailureDetector.shouldTryFallback(primaryFailure)) {
                return Flux.error(primaryFailure);
            }
            log.warn(
                    "LLM[Chat] primary_model_stream_failed model={}, fallback_model={}, reason={}",
                    primaryModelName,
                    fallbackModelName,
                    primaryFailure.getMessage());
            return fallbackModel.stream(prompt);
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return primaryModel.getDefaultOptions();
    }

    String primaryModelName() {
        return primaryModelName;
    }

    String fallbackModelName() {
        return fallbackModelName;
    }

    String effectiveModelLabel() {
        return primaryModelName + "+fallback:" + fallbackModelName;
    }
}
