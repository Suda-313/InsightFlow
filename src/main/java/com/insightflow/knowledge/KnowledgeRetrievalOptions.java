package com.insightflow.knowledge;

/**
 * 单次检索的可选覆盖项；评测 CLI 用其对比 RRF 与精排而不改全局配置。
 *
 * <p>{@code subQueries} 非空时跳过自动分解；{@code questionTypeName} 供 CROSS/VERSION 自动分解。</p>
 */
public record KnowledgeRetrievalOptions(
        boolean rerankerEnabled,
        java.util.List<String> subQueries,
        String questionTypeName) {

    /** 遵循 {@link KnowledgeRerankerProperties#enabled()} 部署默认值。 */
    public static KnowledgeRetrievalOptions defaults() {
        return new KnowledgeRetrievalOptions(false, null, null);
    }

    /** 显式开启或关闭精排，覆盖 application 配置。 */
    public static KnowledgeRetrievalOptions withReranker(boolean enabled) {
        return new KnowledgeRetrievalOptions(enabled, null, null);
    }

    /** 金标评测：传入预计算子查询与题型，避免生产启发式与 gold 不对齐。 */
    public static KnowledgeRetrievalOptions withDecomposition(
            boolean rerankerEnabled, java.util.List<String> subQueries, String questionTypeName) {
        return new KnowledgeRetrievalOptions(
                rerankerEnabled,
                subQueries == null ? null : java.util.List.copyOf(subQueries),
                questionTypeName);
    }

    public KnowledgeRetrievalOptions {
        subQueries = subQueries == null ? null : java.util.List.copyOf(subQueries);
    }
}
