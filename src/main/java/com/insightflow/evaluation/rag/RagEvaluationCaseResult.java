package com.insightflow.evaluation.rag;

/**
 * 一道 RAG 评测题的脱敏规则结果。
 *
 * <p>记录计数与布尔判断即可定位检索、引用或无依据断言问题；不记录企业原文、模型完整回答、
 * 内部主键或思维链，避免评测历史成为新的知识泄露通道。</p>
 */
public record RagEvaluationCaseResult(
        /** 稳定题目标识，用于同一数据集版本内比较。 */
        String caseId,
        /** 页面展示分类，不参与权限或检索判断。 */
        String category,
        /** succeeded 或 failed，单题失败不会中断整批评测。 */
        String status,
        /** 本题金标中期望的文档证据前缀数量。 */
        int expectedEvidenceCount,
        /** 实际检索命中的期望文档数量。 */
        int retrievedExpectedEvidenceCount,
        /** 最终回答声明的知识证据数量。 */
        int citedEvidenceCount,
        /** 引用中确实来自本题检索结果的数量。 */
        int correctCitationCount,
        /** 是否在没有完整可验证引用时作出了知识性断言。 */
        boolean ungrounded) {
}
