package com.insightflow.evaluation.rag;

/**
 * 金标 RAG 评测运行模式。
 *
 * <p>{@link #RETRIEVAL_ONLY} 只走受控检索，不调用回答网关；{@link #END_TO_END} 为完整 RAG 路径。</p>
 */
public enum RagGoldEvaluationRunMode {
    END_TO_END,
    RETRIEVAL_ONLY
}
