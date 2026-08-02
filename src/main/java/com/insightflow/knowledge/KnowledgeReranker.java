package com.insightflow.knowledge;

import java.util.List;

/**
 * 知识检索精排端口：在 RRF 候选集上重排，输出最终 TopK 证据顺序。
 *
 * <p>精排不得改变 Workspace/组织/PUBLISHED 过滤边界；只调整已召回候选的排序。
 * 实现须支持超时或供应商失败时回退 RRF 顺序。</p>
 */
public interface KnowledgeReranker {

    /**
     * 对 RRF 候选重排并截断到 {@code finalLimit}。
     *
     * @param question   用户原问题（embedding 输入不变，精排单独消费）
     * @param candidates RRF 合并后的候选，通常 Top50
     * @param finalLimit 最终保留条数，生产路径为 8
     */
    KnowledgeRerankOutcome rerank(String question, List<KnowledgeVectorStore.SearchCandidate> candidates, int finalLimit);
}
