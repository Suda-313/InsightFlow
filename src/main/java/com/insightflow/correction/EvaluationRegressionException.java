package com.insightflow.correction;

/** 任一评测门禁回归时阻止纠错候选发布的受控异常。 */
public class EvaluationRegressionException extends RuntimeException {
    /** 仅携带机器可处理的回归维度，不暴露评测原始答案或内部堆栈。 */
    public EvaluationRegressionException(String message) { super(message); }
}
