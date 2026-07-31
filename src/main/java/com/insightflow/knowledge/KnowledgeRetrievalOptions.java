package com.insightflow.knowledge;

/**
 * 单次检索的可选覆盖项；评测 CLI 用其对比 RRF 与精排而不改全局配置。
 *
 * <p>{@code subQueries} 非空时跳过自动分解；{@code questionTypeName} 供 CROSS/VERSION 自动分解。</p>
 */
public record KnowledgeRetrievalOptions(
        boolean rerankerEnabled,
        java.util.List<String> subQueries,
        String questionTypeName,
        boolean identifierSupplementEnabled,
        boolean subQueryQuotaEnabled,
        /** 后验证据门控；false 时行为与门控引入前逐字节一致。 */
        boolean evidenceGateEnabled) {

    /** 遵循 {@link KnowledgeRerankerProperties#enabled()} 部署默认值；门控默认开启。 */
    public static KnowledgeRetrievalOptions defaults() {
        return new KnowledgeRetrievalOptions(false, null, null, true, true, true);
    }

    /** 显式开启或关闭精排，覆盖 application 配置。 */
    public static KnowledgeRetrievalOptions withReranker(boolean enabled) {
        return new KnowledgeRetrievalOptions(enabled, null, null, true, true, true);
    }

    /** 金标评测：传入预计算子查询与题型，避免生产启发式与 gold 不对齐。 */
    public static KnowledgeRetrievalOptions withDecomposition(
            boolean rerankerEnabled, java.util.List<String> subQueries, String questionTypeName) {
        return withDecomposition(rerankerEnabled, subQueries, questionTypeName, true, true, true);
    }

    /** Phase 4A 消融：在分解参数之上显式开关 identifier / subquota / gate 实验项。 */
    public static KnowledgeRetrievalOptions withDecomposition(
            boolean rerankerEnabled,
            java.util.List<String> subQueries,
            String questionTypeName,
            boolean identifierSupplementEnabled,
            boolean subQueryQuotaEnabled) {
        return withDecomposition(
                rerankerEnabled,
                subQueries,
                questionTypeName,
                identifierSupplementEnabled,
                subQueryQuotaEnabled,
                true);
    }

    /** 完整消融入口：可单独关闭后验证据门控以对比 Phase 4 基线。 */
    public static KnowledgeRetrievalOptions withDecomposition(
            boolean rerankerEnabled,
            java.util.List<String> subQueries,
            String questionTypeName,
            boolean identifierSupplementEnabled,
            boolean subQueryQuotaEnabled,
            boolean evidenceGateEnabled) {
        return new KnowledgeRetrievalOptions(
                rerankerEnabled,
                subQueries == null ? null : java.util.List.copyOf(subQueries),
                questionTypeName,
                identifierSupplementEnabled,
                subQueryQuotaEnabled,
                evidenceGateEnabled);
    }

    public KnowledgeRetrievalOptions {
        subQueries = subQueries == null ? null : java.util.List.copyOf(subQueries);
    }
}
