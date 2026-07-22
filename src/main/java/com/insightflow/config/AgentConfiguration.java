package com.insightflow.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 层配置，集中装配 ChatClient 等与 LLM 交互所需的基础 Bean。
 */
@Configuration
public class AgentConfiguration {

    /**
     * 基于 OpenAI 兼容接口的 ChatClient Bean（对接百炼平台 DeepSeek 模型）。
     */
    @Bean
    ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.create(chatModel);
    }
}
