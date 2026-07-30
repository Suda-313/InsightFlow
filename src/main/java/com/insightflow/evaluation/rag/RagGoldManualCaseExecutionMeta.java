package com.insightflow.evaluation.rag;

/**
 * 单题执行元数据，供性能分位与失败统计；不含问题或回答正文。
 *
 * <p>耗时字段可空：carry-forward 自历史 JSON 合并时若无样本则为 null，分位聚合须跳过而非填 0。</p>
 */
public record RagGoldManualCaseExecutionMeta(
        String caseKey,
        String status,
        String failureStage,
        Long retrievalLatencyMs,
        Long generationLatencyMs,
        Long totalLatencyMs) {
}
