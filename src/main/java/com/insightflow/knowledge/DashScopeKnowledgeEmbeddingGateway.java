package com.insightflow.knowledge;

import com.insightflow.config.AgentApiKeyPresentCondition;
import java.util.List;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** 使用当前 DashScope OpenAI-compatible 模型生成真实 embedding 的适配器。 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class DashScopeKnowledgeEmbeddingGateway implements KnowledgeEmbeddingGateway {
    /** Spring AI 模型仅在 Agent 密钥存在时创建，避免基础服务启动时请求或校验密钥。 */
    private final OpenAiEmbeddingModel embeddingModel;

    /** 注入受配置约束的模型，业务服务不保存供应商地址或密钥。 */
    public DashScopeKnowledgeEmbeddingGateway(OpenAiEmbeddingModel embeddingModel) { this.embeddingModel = embeddingModel; }

    /** 保持输入/输出顺序，让 chunk_no 与向量一一对应。 */
    @Override
    public List<List<Double>> embed(List<String> contents) { return embeddingModel.embed(contents); }
}
