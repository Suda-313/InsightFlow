package com.insightflow.evaluation.rag;

import java.util.Set;

/**
 * 单题执行后的最小可审计观测值。
 *
 * <p>执行器只报告已检索证据、回答实际引用的证据和是否给出知识性断言；
 * 不保存模型原始思维链，也不要求第二个模型参与主观打分。</p>
 */
public record RagEvaluationObservation(
        Set<String> retrievedEvidenceIds,
        Set<String> citedEvidenceIds,
        boolean containsKnowledgeClaim) {

    /** 所有集合均不可变，确保同一观测输入可以被重复评分。 */
    public RagEvaluationObservation {
        retrievedEvidenceIds = Set.copyOf(retrievedEvidenceIds);
        citedEvidenceIds = Set.copyOf(citedEvidenceIds);
    }
}
