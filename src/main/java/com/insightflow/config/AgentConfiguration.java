package com.insightflow.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.dashscope.DashscopeChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 层配置，集中装配 ChatClient 等与 LLM 交互所需的基础 Bean。
 */
@Configuration
public class AgentConfiguration {

    /**
     * 基于 DashScope 的 ChatClient Bean，供各类 Analyzer 注入使用。
     *
     * @param chatModel DashScope 聊天模型
     * @return ChatClient 实例
     */
    @Bean
    ChatClient chatClient(DashscopeChatModel chatModel) {
        return ChatClient.create(chatModel);
    }
}
