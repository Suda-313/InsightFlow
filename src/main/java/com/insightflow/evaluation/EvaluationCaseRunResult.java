package com.insightflow.evaluation;

/**
 * 单条金标题目的一次模型运行结果。
 *
 * <p>输出文本仅来自项目内固定脱敏 fixture，可用于开发排障；真实用户会话不进入此模型。</p>
 */
public record EvaluationCaseRunResult(
        /** 金标题目稳定标识。 */
        String caseId,
        /** 题目类别，便于按意图观察回归。 */
        String category,
        /** succeeded 或 failed，单题失败不影响其余题目。 */
        String status,
        /** 规则评分；模型调用失败时为 null。 */
        EvaluationCaseScore score,
        /** 固定 fixture 下的最终模型回答。 */
        String outputText,
        /** 服务端模型调用耗时，不包含触发 API 的网络时间。 */
        Long latencyMs,
        /** 服务商返回的输入 Token，缺失时为 null。 */
        Long promptTokens,
        /** 服务商返回的输出 Token，缺失时为 null。 */
        Long completionTokens,
        /** 服务商返回的总 Token，缺失时为 null。 */
        Long totalTokens,
        /** 受控失败阶段，不暴露上游异常正文。 */
        String errorStage) {
}
