package com.insightflow.knowledge;

import com.insightflow.config.AgentApiKeyPresentCondition;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 使用 DashScope OpenAI-compatible embedding 接口生成知识切片向量。
 *
 * <p>DashScope 单次请求最多 10 条 input.contents；发布长文档时 chunk 数常超过该上限，
 * 必须在网关层按固定批次拆分并保持输出顺序与 chunk_no 一一对应。</p>
 */
@Component
@Conditional(AgentApiKeyPresentCondition.class)
public class DashScopeKnowledgeEmbeddingGateway implements KnowledgeEmbeddingGateway {

    /** DashScope text-embedding 系列 API 的硬上限；超出会直接 400 InvalidParameter。 */
    static final int DASHSCOPE_MAX_BATCH_SIZE = 10;

    /** Spring AI 模型仅在 Agent 密钥存在时创建，避免基础服务启动时请求或校验密钥。 */
    private final OpenAiEmbeddingModel embeddingModel;

    /** 注入受配置约束的模型，业务服务不保存供应商地址或密钥。 */
    public DashScopeKnowledgeEmbeddingGateway(OpenAiEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 按 DashScope 批次上限拆分请求；返回列表长度与输入一致，供 chunk_no 对齐写入。
     */
    @Override
    public List<List<Double>> embed(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<List<Double>> all = new ArrayList<>(contents.size());
        for (int offset = 0; offset < contents.size(); offset += DASHSCOPE_MAX_BATCH_SIZE) {
            int end = Math.min(offset + DASHSCOPE_MAX_BATCH_SIZE, contents.size());
            for (float[] embedding : embeddingModel.embed(contents.subList(offset, end))) {
                List<Double> vector = new ArrayList<>(embedding.length);
                for (float value : embedding) {
                    vector.add((double) value);
                }
                all.add(vector);
            }
        }
        return all;
    }
}
