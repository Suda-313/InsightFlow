package com.insightflow.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 仅装配 InsightFlow 实际使用的聊天模型。
 *
 * <p>Spring AI 1.1.0 的 OpenAI 自动配置还会装配本项目未使用的音频模型，空密钥时会阻止基础服务启动。
 * 因此关闭该自动配置，并由本类在 Agent 已显式启用且密钥存在时创建唯一需要的聊天模型。</p>
 */
@Configuration
@Conditional(AgentApiKeyPresentCondition.class)
public class AgentConfiguration {

    /**
     * 将部署配置转为 DashScope 兼容接口的聊天模型。
     *
     * <p>不创建嵌入、图像或音频模型，避免无业务用途的运行时依赖。模型参数保留在 application.yml，
     * 便于部署时通过环境变量或配置中心替换，而不把供应商配置散落到聊天服务和分析器中。</p>
     *
     * @param restClientBuilder 同步调用使用的 HTTP 客户端构建器，由 Spring Boot 管理连接和观测能力
     * @param webClientBuilder 流式能力所需的响应式 HTTP 客户端构建器，即使当前聊天接口不对外流式返回也需保留
     * @param baseUrl DashScope 的 OpenAI 兼容接口地址，不在业务代码中写死具体供应商域名
     * @param apiKey 仅从部署配置注入的密钥，条件已保证它非空且不会被日志记录
     * @param model 实际调用的模型版本，用于让 AgentRun 与真实请求保持一致
     * @param temperature 回答随机性，保持较低值以优先保证分析结论稳定
     * @param maxTokens 单次输出上限，防止单个对话挤占过多成本和响应时间
     */
    @Bean
    OpenAiChatModel openAiChatModel(
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            @Value("${spring.ai.openai.chat.options.temperature}") Float temperature,
            @Value("${spring.ai.openai.chat.options.max-tokens}") Integer maxTokens) {
        // 每次启动根据配置构造独立选项，避免共享可变对象被其他模型能力修改。
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(model);
        options.setTemperature(temperature);
        options.setMaxTokens(maxTokens);
        // OpenAiApi 只保存连接配置；这里不会发起网络请求或验证密钥有效性。
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey, restClientBuilder, webClientBuilder);
        return new OpenAiChatModel(openAiApi, options);
    }

    /**
     * ChatClient 只承接提示词和模型调用；会话、权限、审计记录仍由 Service 层负责。
     */
    @Bean
    ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    /**
     * 创建 P3 知识发布唯一使用的 embedding 模型。
     *
     * <p>它与聊天模型复用同一受控的 DashScope OpenAI-compatible 连接配置，但模型名和维度独立配置；
     * 不能由上传接口指定，避免文档版本之间混入不兼容向量。</p>
     */
    @Bean
    OpenAiEmbeddingModel knowledgeEmbeddingModel(
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${insightflow.knowledge.embedding-model:text-embedding-v3}") String model,
            @Value("${insightflow.knowledge.embedding-dimensions:1024}") Integer dimensions) {
        OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();
        options.setModel(model);
        options.setDimensions(dimensions);
        return new OpenAiEmbeddingModel(new OpenAiApi(baseUrl, apiKey, restClientBuilder, webClientBuilder),
                org.springframework.ai.document.MetadataMode.NONE, options);
    }
}
