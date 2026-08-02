package com.insightflow.knowledge;

import java.util.List;

/**
 * 专用精排模型网关：批量对 query-document 对打分，不经过聊天模型。
 */
public interface KnowledgeRerankGateway {

    /**
     * 按相关性对文档列表重排。
     *
     * @param query     用户问题
     * @param documents 候选文档文本，顺序与调用方索引一致
     * @param topN      返回前 N 个索引（通常等于输入数或 finalLimit）
     * @return 按相关性降序的原始索引与分数
     */
    List<RerankScore> rerank(String query, List<String> documents, int topN);

    /** 单条精排分数；index 指向输入 documents 列表下标。 */
    record RerankScore(int index, double relevanceScore) {
    }
}
