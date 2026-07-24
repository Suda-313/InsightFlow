package com.insightflow.evaluation.rag;

import java.util.Set;

/**
 * 一道可重复执行的 RAG 评测题定义。
 *
 * <p>题目只保存固定问题和稳定文档证据前缀，不能保存企业原文、模型回答或内部主键；
 * 文档的版本与切片可变化，因此期望值匹配的是 {@code knowledge:{document-public-id}:} 前缀。</p>
 */
public record RagEvaluationCaseDefinition(
        /** 在同一数据集版本内稳定的题目标识。 */
        String caseId,
        /** 用于评测页面归类，不参与模型提示或权限决策。 */
        String category,
        /** 直接交给受控 RAG 链路的问题，不携带原文内容。 */
        String question,
        /** 预期被召回的文档证据前缀集合；空集合表示应如实说明知识缺口。 */
        Set<String> expectedEvidencePrefixes) {

    /** 固化集合，避免调用方在模型执行过程中篡改金标预期。 */
    public RagEvaluationCaseDefinition {
        expectedEvidencePrefixes = Set.copyOf(expectedEvidencePrefixes);
    }
}
