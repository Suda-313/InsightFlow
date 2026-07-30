package com.insightflow.evaluation.rag;

/**
 * RAG 专项金标指标。
 *
 * <p>召回率按预期证据集合聚合；引用正确性只判断引用是否来自当题实际检索结果；
 * 无依据回答率衡量模型在作出知识性断言时是否缺少有效引用，三项均由确定性规则计算。</p>
 */
public record RagEvaluationMetrics(
        double retrievalRecallRate,
        double citationCorrectnessRate,
        double ungroundedAnswerRate,
        int caseCount,
        /** 人工金标扩展指标；动态 Fixture 评测为 null。 */
        RagGoldManualExtendedMetrics extended) {

    /** 兼容既有三项 legacy 指标调用方。 */
    public RagEvaluationMetrics(
            double retrievalRecallRate,
            double citationCorrectnessRate,
            double ungroundedAnswerRate,
            int caseCount) {
        this(retrievalRecallRate, citationCorrectnessRate, ungroundedAnswerRate, caseCount, null);
    }
}
