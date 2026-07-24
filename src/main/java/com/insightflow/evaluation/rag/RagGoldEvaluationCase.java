package com.insightflow.evaluation.rag;

import java.util.Set;

/**
 * 一条 RAG 金标题目的证据预期。
 *
 * <p>预期集合只包含脱敏、稳定的证据 ID；它不保存知识原文、内部主键或模型答案，
 * 从而可用于评估检索变化而不把企业文档复制进评测代码。</p>
 */
public record RagGoldEvaluationCase(String caseId, Set<String> expectedEvidenceIds) {

    /** 固化输入集合，防止评测执行期间调用方修改金标答案。 */
    public RagGoldEvaluationCase {
        expectedEvidenceIds = Set.copyOf(expectedEvidenceIds);
    }
}
