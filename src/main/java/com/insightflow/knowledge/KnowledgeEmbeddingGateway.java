package com.insightflow.knowledge;

import java.util.List;

/**
 * 知识嵌入的受控边界。
 *
 * <p>发布服务只能传递已切片的文本并获得数值向量；它不知道供应商、密钥或 HTTP 细节，
 * 因而无法在无密钥时静默退化为文本检索。</p>
 */
public interface KnowledgeEmbeddingGateway {

    /** 为同一待发布版本的全部切片生成等长向量，返回顺序必须与输入一致。 */
    List<List<Double>> embed(List<String> contents);
}
