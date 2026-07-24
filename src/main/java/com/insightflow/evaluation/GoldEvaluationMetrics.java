package com.insightflow.evaluation;

/**
 * 一次金标批次的可比较汇总指标。
 *
 * <p>事实覆盖率和禁止断言命中率是规则代理指标；它们用于尽早发现回归，
 * 不能替代对因果判断、语气和业务价值的人工复核。</p>
 */
public record GoldEvaluationMetrics(
        /** 本批次计划运行的题目数量。 */
        int totalCaseCount,
        /** 成功取得模型回答并完成规则评分的题目数量。 */
        int succeededCaseCount,
        /** 模型调用或 fixture 加载失败的题目数量。 */
        int failedCaseCount,
        /** 必含事实命中数除以全部必含事实数。 */
        double factCoverageRate,
        /** 禁止断言命中数除以全部禁止断言数，越低越好。 */
        double forbiddenClaimHitRate,
        /** 预期拒答题的合规比例；没有拒答题时为 null。 */
        Double refusalComplianceRate,
        /** 所有已完成模型调用的服务端耗时累计。 */
        long totalLatencyMs,
        /** 可用时的累计输入 Token；服务商未返回 Usage 时为 null。 */
        Long totalPromptTokens,
        /** 可用时的累计输出 Token；服务商未返回 Usage 时为 null。 */
        Long totalCompletionTokens,
        /** 可用时的累计总 Token；服务商未返回 Usage 时为 null。 */
        Long totalTokens,
        /** 成功题目服务端耗时的 p50；无成功题目时为 null。*/
        Long p50LatencyMs,
        /** 成功题目服务端耗时的 p95；用于识别尾部慢请求。*/
        Long p95LatencyMs,
        /** 输入 Token 的 p50；任一成功题未返回 Usage 时为 null。*/
        Long p50PromptTokens,
        /** 输入 Token 的 p95；用于定位上下文膨胀。*/
        Long p95PromptTokens,
        /** 输出 Token 的 p50；任一成功题未返回 Usage 时为 null。*/
        Long p50CompletionTokens,
        /** 输出 Token 的 p95；用于限制无效长输出。*/
        Long p95CompletionTokens,
        /** 覆盖至少一项必要事实的成功回答占比，是规则化回答具体性代理。*/
        double answerSpecificityRate,
        /** 使用 P2 证据引用标记的成功回答占比，只反映格式合规而非引用语义正确性。 */
        double evidenceCitationRate) {

    /** 兼容 P1 指标 JSON 与既有调用方；历史批次没有引用覆盖率时按零处理。 */
    public GoldEvaluationMetrics(
            int totalCaseCount,
            int succeededCaseCount,
            int failedCaseCount,
            double factCoverageRate,
            double forbiddenClaimHitRate,
            Double refusalComplianceRate,
            long totalLatencyMs,
            Long totalPromptTokens,
            Long totalCompletionTokens,
            Long totalTokens,
            Long p50LatencyMs,
            Long p95LatencyMs,
            Long p50PromptTokens,
            Long p95PromptTokens,
            Long p50CompletionTokens,
            Long p95CompletionTokens,
            double answerSpecificityRate) {
        this(totalCaseCount, succeededCaseCount, failedCaseCount, factCoverageRate, forbiddenClaimHitRate,
                refusalComplianceRate, totalLatencyMs, totalPromptTokens, totalCompletionTokens, totalTokens,
                p50LatencyMs, p95LatencyMs, p50PromptTokens, p95PromptTokens, p50CompletionTokens,
                p95CompletionTokens, answerSpecificityRate, 0.0);
    }
}
