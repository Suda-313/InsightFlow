package com.insightflow.evaluation;

import java.util.List;

/**
 * 固定金标集的一次完整运行结果。
 *
 * <p>数据集、Prompt 和模型版本必须同时返回，缺少其中任一维度的结果都不能用于策略回归比较。</p>
 */
public record GoldEvaluationRunResult(
        /** 金标样本版本。 */
        String datasetVersion,
        /** 实际参与模型调用的 Prompt 版本。 */
        String promptVersion,
        /** 实际请求的模型名称。 */
        String modelName,
        /** 按金标集顺序返回的逐题结果。 */
        List<EvaluationCaseRunResult> caseResults,
        /** 可用于比较的规则质量、延迟和 Token 汇总。 */
        GoldEvaluationMetrics metrics) {

    /** 防止 API 调用方修改逐题结果集合，保证汇总与明细保持一致。 */
    public GoldEvaluationRunResult {
        caseResults = List.copyOf(caseResults);
    }
}
