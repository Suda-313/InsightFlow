package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.KnowledgeDocumentType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 后验证据门控的双阈值判定。 */
class KnowledgeEvidenceGuardrailTest {

    private KnowledgeEvidenceGuardrail guardrail;

    @BeforeEach
    void setUp() {
        guardrail = new KnowledgeEvidenceGuardrail();
    }

    @Test
    void abstainsWhenTopScoreBelowRrfThreshold() {
        KnowledgeEvidenceGateDecision decision = guardrail.decide(
                List.of(candidate(0.015d)), false);

        assertThat(decision.outcome()).isEqualTo(KnowledgeEvidenceGateDecision.OUTCOME_ABSTAIN);
        assertThat(decision.injected()).isEmpty();
        assertThat(decision.topScore()).isEqualTo(0.015d);
    }

    @Test
    void injectsWhenTopScoreMeetsRrfThreshold() {
        KnowledgeEvidenceGateDecision decision = guardrail.decide(
                List.of(candidate(0.05d), candidate(0.01d)), false);

        assertThat(decision.outcome()).isEqualTo(KnowledgeEvidenceGateDecision.OUTCOME_INJECT);
        assertThat(decision.injected()).hasSize(1);
        assertThat(decision.injected().get(0).score()).isEqualTo(0.05d);
    }

    @Test
    void filtersLowScoreTailBeforeInject() {
        KnowledgeEvidenceGateDecision decision = guardrail.decide(
                List.of(candidate(0.05d), candidate(0.017d), candidate(0.01d)), false);

        assertThat(decision.outcome()).isEqualTo(KnowledgeEvidenceGateDecision.OUTCOME_INJECT);
        assertThat(decision.injected()).hasSize(2);
    }

    @Test
    void usesRerankThresholdsWhenRerankScoresActive() {
        KnowledgeEvidenceGateDecision decision = guardrail.decide(
                List.of(candidate(0.40d), candidate(0.20d)), true);

        assertThat(decision.outcome()).isEqualTo(KnowledgeEvidenceGateDecision.OUTCOME_INJECT);
        assertThat(decision.injected()).hasSize(1);
        assertThat(decision.injected().get(0).score()).isEqualTo(0.40d);
    }

    @Test
    void abstainsOnEmptyCandidates() {
        KnowledgeEvidenceGateDecision decision = guardrail.decide(List.of(), false);

        assertThat(decision.outcome()).isEqualTo(KnowledgeEvidenceGateDecision.OUTCOME_ABSTAIN);
        assertThat(decision.inputCount()).isZero();
    }

    @Test
    void forceAbstainsForChitchatQuestionTypeEvenWithHighScore() {
        KnowledgeEvidenceGateDecision decision = guardrail.decide(
                List.of(candidate(0.50d)), false, true);

        assertThat(decision.outcome()).isEqualTo(KnowledgeEvidenceGateDecision.OUTCOME_ABSTAIN);
        assertThat(decision.injected()).isEmpty();
        assertThat(decision.topScore()).isEqualTo(0.50d);
    }

    @Test
    void shouldForceAbstainForChitchatAndNoAnswerTypes() {
        assertThat(guardrail.shouldForceAbstain("Steam版什么时候出？", "NO_ANSWER")).isTrue();
        assertThat(guardrail.shouldForceAbstain("你好", "CHITCHAT")).isTrue();
        assertThat(guardrail.shouldForceAbstain("1.4 登录异常怎么处理？", "SINGLE_DOCUMENT_FACT")).isFalse();
    }

    @Test
    void shouldForceAbstainForCommonGreetingsWithoutQuestionType() {
        assertThat(guardrail.shouldForceAbstain("早上好", null)).isTrue();
        assertThat(guardrail.shouldForceAbstain("你能做什么？", null)).isTrue();
        assertThat(guardrail.shouldForceAbstain("1.4 版本维护窗口是几点？", null)).isFalse();
    }

    private static KnowledgeVectorStore.SearchCandidate candidate(double score) {
        return new KnowledgeVectorStore.SearchCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "title",
                "content",
                score,
                KnowledgeDocumentType.RELEASE_NOTE.name(),
                "section",
                null);
    }
}
