package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldAssertionSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldCaseSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RagGoldManualEvaluationCarryForwardSupportTest {

    private static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID VER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CHUNK = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    void reconstructsScoreFromExactCarriedHitFields() {
        RagGoldCaseSnapshot goldCase = goldCase();
        RagGoldManualEvaluationCaseResult carried = new RagGoldManualEvaluationCaseResult(
                "dev-001", "succeeded", null, null, "SINGLE_DOCUMENT_FACT",
                1, 1, 1, 1, false, 1.0, 0.0, true,
                false, true, true, false, true, true, 0.5, 0.75,
                100L, 2000L, 2100L, null);

        RagGoldManualCaseScore score = RagGoldManualEvaluationCarryForwardSupport.toScore(goldCase, carried);

        assertThat(score.documentHitAt1()).isFalse();
        assertThat(score.documentHitAt3()).isTrue();
        assertThat(score.chunkHitAt1()).isFalse();
        assertThat(score.reciprocalRank()).isEqualTo(0.5);
        assertThat(score.ndcgAt8()).isEqualTo(0.75);
    }

    @Test
    void restoresLatencyMetaWithoutZeroPadding() {
        RagGoldManualEvaluationCaseResult carried = new RagGoldManualEvaluationCaseResult(
                "dev-001", "succeeded", null, null, null,
                null, null, null, null, null, null, null, null,
                true, true, true, true, true, true, 1.0, 1.0,
                586L, 15643L, 16231L, null);

        RagGoldManualCaseExecutionMeta meta = RagGoldManualEvaluationCarryForwardSupport.toExecutionMeta(carried);

        assertThat(meta.retrievalLatencyMs()).isEqualTo(586L);
        assertThat(meta.generationLatencyMs()).isEqualTo(15643L);
        assertThat(meta.totalLatencyMs()).isEqualTo(16231L);
    }

    @Test
    void fallsBackToLegacyApproximationWhenHitFieldsMissing() {
        RagGoldCaseSnapshot goldCase = goldCase();
        RagGoldManualEvaluationCaseResult legacy = new RagGoldManualEvaluationCaseResult(
                "dev-001", "succeeded", null, null, "SINGLE_DOCUMENT_FACT",
                1, 1, 1, 1, false, 1.0, 0.0, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null);

        RagGoldManualCaseScore score = RagGoldManualEvaluationCarryForwardSupport.toScore(goldCase, legacy);

        assertThat(score.documentHitAt1()).isTrue();
        assertThat(score.documentHitAt3()).isTrue();
        assertThat(score.reciprocalRank()).isEqualTo(1.0);
    }

    private RagGoldCaseSnapshot goldCase() {
        return new RagGoldCaseSnapshot(
                UUID.randomUUID(),
                "dev-001",
                "question",
                RagGoldQuestionType.SINGLE_DOCUMENT_FACT,
                RagGoldDifficulty.EASY,
                false,
                "basis",
                "reviewer",
                List.of(new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.CHUNK, DOC, VER, CHUNK)),
                List.of(new RagGoldAssertionSnapshot(RagGoldAssertionType.REQUIRED_FACT, "fact", 1.0)));
    }
}
