package com.insightflow.evaluation.rag.gold;

import com.insightflow.entity.RagGoldAssertionType;

/** 一条断言的只读快照。 */
public record RagGoldAssertionSnapshot(
        RagGoldAssertionType assertionType,
        String assertionText,
        double weight) {
}
