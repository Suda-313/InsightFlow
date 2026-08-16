package com.insightflow.config;

import com.insightflow.prompt.LiteralChatModelCaller;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
     * 将部署配置转为 DashScope 兼容接口的聊天模型；可选装配备用模型并在额度/限流失败时自动切换。
     */
    @Bean
    ChatModel chatModel(
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            @Value("${insightflow.agent.fallback-chat-model:}") String fallbackModel,
            @Value("${spring.ai.openai.chat.options.temperature}") Float temperature,
            @Value("${spring.ai.openai.chat.options.max-tokens}") Integer maxTokens,
            @Value("${insightflow.agent.http-read-timeout-seconds:110}") long httpReadTimeoutSeconds) {
        OpenAiChatModel primary = buildChatModel(
                restClientBuilder,
                webClientBuilder,
                baseUrl,
                apiKey,
                model,
                temperature,
                maxTokens,
                httpReadTimeoutSeconds);
        if (fallbackModel == null || fallbackModel.isBlank()) {
            return primary;
        }
        OpenAiChatModel fallback = buildChatModel(
                restClientBuilder,
                webClientBuilder,
                baseUrl,
                apiKey,
                fallbackModel,
                temperature,
                maxTokens,
                httpReadTimeoutSeconds);
        return new FallbackChatModel(primary, fallback, model, fallbackModel);
    }

    /**
     * ChatClient 只承接提示词和模型调用；会话、权限、审计记录仍由 Service 层负责。
     */
    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    /**
     * 不经 ChatClient 模板引擎调用模型；与 chatClient 共用同一 ChatModel，避免 {@code {版本号}} 等字面量被误解析。
     */
    @Bean
    LiteralChatModelCaller literalChatModelCaller(ChatModel chatModel) {
        return new LiteralChatModelCaller(chatModel);
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
            @Value("${insightflow.knowledge.embedding-dimensions:1024}") Integer dimensions,
            @Value("${insightflow.agent.http-read-timeout-seconds:110}") long httpReadTimeoutSeconds) {
        OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();
        options.setModel(model);
        options.setDimensions(dimensions);
        return new OpenAiEmbeddingModel(OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(withNetworkTimeout(restClientBuilder, httpReadTimeoutSeconds))
                .webClientBuilder(webClientBuilder)
                .build(),
                org.springframework.ai.document.MetadataMode.NONE, options);
    }

    private OpenAiChatModel buildChatModel(
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            String baseUrl,
            String apiKey,
            String model,
            Float temperature,
            Integer maxTokens,
            long httpReadTimeoutSeconds) {
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(model);
        options.setTemperature(temperature == null ? null : temperature.doubleValue());
        options.setMaxTokens(maxTokens);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(withNetworkTimeout(restClientBuilder, httpReadTimeoutSeconds))
                .webClientBuilder(webClientBuilder)
                .build();
        return OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(options).build();
    }

    /**
     * 为同步的 OpenAI-compatible 调用同时设置连接与响应读取上限。
     *
     * <p>读取上限必须小于 RAG 单题应用层上限（默认 120 秒），使底层 HTTP 先释放线程；
     * 应用层 Future 取消仍保留为供应商客户端未及时响应中断时的第二道保护。</p>
     */
    private RestClient.Builder withNetworkTimeout(RestClient.Builder builder, long timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.requestFactory(requestFactory);
    }
}
