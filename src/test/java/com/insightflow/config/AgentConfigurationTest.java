package com.insightflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Agent 运行时是可选能力：未配置模型密钥时，基础分析服务仍可创建 Spring 容器。
 */
class AgentConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentConfiguration.class)
            .withPropertyValues("spring.ai.openai.api-key=");

    /**
     * 空密钥不能触发 OpenAI 模型及其 ChatClient 的装配，否则本地仅使用基础分析功能也会启动失败。
     */
    @Test
    void skipsChatClientWhenApiKeyIsBlank() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatClient.class);
        });
    }

    /**
     * 显式启用且给出非空密钥时，仅创建项目需要的聊天客户端，不在启动阶段请求外部模型服务。
     */
    @Test
    void createsChatClientWhenAgentIsEnabledAndApiKeyIsPresent() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentConfiguration.class, HttpClientConfiguration.class)
                .withPropertyValues(
                        "insightflow.agent.enabled=true",
                        "spring.ai.openai.api-key=test-key",
                        "spring.ai.openai.base-url=https://example.com",
                        "spring.ai.openai.chat.options.model=test-model",
                        "spring.ai.openai.chat.options.temperature=0.3",
                        "spring.ai.openai.chat.options.max-tokens=2000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatClient.class);
                });
    }

    /** 为上下文测试提供模型构造所需的 HTTP Builder，不发起真实网络请求。 */
    @Configuration(proxyBeanMethods = false)
    static class HttpClientConfiguration {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder();
        }
    }

}
