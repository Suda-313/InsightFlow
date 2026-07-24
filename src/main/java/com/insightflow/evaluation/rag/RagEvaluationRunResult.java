package com.insightflow.evaluation.rag;

import java.util.List;

/**
 * 一次真实受控 RAG 评测的不可变运行结果。
 *
 * <p>版本字段使每次评测可定位到题集、Prompt、模型和检索策略；逐题结果仅保留脱敏计数，
 * 因而既能比较回归，也不会把企业资料或模型回答复制进数据库。</p>
 */
public record RagEvaluationRunResult(
        /** 当前可见已发布知识计算出的金标数据集版本。 */
        String datasetVersion,
        /** 与线上聊天共用的提示词护栏版本。 */
        String promptVersion,
        /** 本次实际调用的模型名称。 */
        String modelName,
        /** 受控 FTS + pgvector + RRF 检索实现版本。 */
        String retrievalVersion,
        /** 三项确定性聚合指标。 */
        RagEvaluationMetrics metrics,
        /** 固化的逐题脱敏结果。 */
        List<RagEvaluationCaseResult> caseResults) {

    /** 固化列表防止持久化前被调用方替换或追加结果。 */
    public RagEvaluationRunResult {
        caseResults = List.copyOf(caseResults);
    }
}
