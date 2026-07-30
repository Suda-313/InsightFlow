package com.insightflow.evaluation.rag.gold;

import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldQuestionType;
import java.util.List;
import java.util.UUID;

/** 单题只读快照；不包含内部数据库 id。 */
public record RagGoldCaseSnapshot(
        UUID casePublicId,
        String caseKey,
        String questionText,
        RagGoldQuestionType questionType,
        RagGoldDifficulty difficulty,
        boolean shouldRefuse,
        String annotationBasis,
        String reviewer,
        List<RagGoldEvidenceSnapshot> evidences,
        List<RagGoldAssertionSnapshot> assertions) {
}
