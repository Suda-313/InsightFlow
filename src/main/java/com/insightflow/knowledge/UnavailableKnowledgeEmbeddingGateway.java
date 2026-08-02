package com.insightflow.knowledge;

import java.util.List;
import com.insightflow.config.AgentApiKeyAbsentCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** 无 Agent 密钥时的安全兜底：允许上传待审核文档，但拒绝发布可检索版本。 */
@Component
@Conditional(AgentApiKeyAbsentCondition.class)
public class UnavailableKnowledgeEmbeddingGateway implements KnowledgeEmbeddingGateway {

    /** 绝不生成占位向量，避免半成品知识误进入 pgvector 检索。 */
    @Override
    public List<List<Double>> embed(List<String> contents) {
        throw new IllegalStateException("未配置嵌入模型，知识版本不能发布");
    }
}
