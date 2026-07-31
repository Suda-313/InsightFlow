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
        List<RagGoldAssertionSnapshot> assertions,
        /** 多轮评测的前序对话；null 或空表示单轮自足题。 */
        List<RagGoldContextTurnSnapshot> contextTurns) {

    /** 兼容旧快照构造：无 contextTurns 时视为单轮题。 */
    public RagGoldCaseSnapshot(
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
        this(
                casePublicId,
                caseKey,
                questionText,
                questionType,
                difficulty,
                shouldRefuse,
                annotationBasis,
                reviewer,
                evidences,
                assertions,
                null);
    }
}
