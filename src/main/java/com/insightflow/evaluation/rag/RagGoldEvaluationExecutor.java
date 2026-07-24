package com.insightflow.evaluation.rag;

/**
 * RAG 金标题目的执行边界。
 *
 * <p>生产实现可调用受控检索与聊天链路，测试实现可返回固定观测；评分器不直接依赖模型、
 * 数据库或 HTTP，从而保证指标计算本身可重复且不会把模型自评当作质量结论。</p>
 */
@FunctionalInterface
public interface RagGoldEvaluationExecutor {

    /** 执行一条固定题目并只返回计算指标所需的受控观测值。 */
    RagEvaluationObservation execute(RagGoldEvaluationCase evaluationCase);
}
