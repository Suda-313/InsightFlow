package com.insightflow.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 规则评分测试：首版指标只对明确的金标事实和禁止断言负责，不把语义相近误判为严格通过。
 */
class EvaluationCaseScorerTest {

    /**
     * 回答覆盖两个必含事实、遗漏一个事实且包含一条禁止断言时，应返回可解释的逐项统计。
     */
    @Test
    void scoresRequiredFactsForbiddenClaimsAndRefusalCompliance() {
        GoldEvaluationCase evaluationCase = new GoldEvaluationCase(
                "case-1", "game-support:v1", "refusal", "能否确认根因？",
                List.of("缺少版本标签", "无法确认根因", "需要样本反馈"),
                List.of("确定是服务器故障"), true);

        EvaluationCaseScore score = new EvaluationCaseScorer().score(
                evaluationCase, "当前缺少版本标签，无法确认根因；需要样本反馈继续排查。确定是服务器故障。");

        assertThat(score.requiredFactCount()).isEqualTo(3);
        assertThat(score.coveredRequiredFactCount()).isEqualTo(3);
        assertThat(score.forbiddenClaimCount()).isEqualTo(1);
        assertThat(score.hitForbiddenClaimCount()).isEqualTo(1);
        assertThat(score.refusalCompliant()).isFalse();
        assertThat(score.answerSpecific()).isTrue();
        assertThat(score.evidenceCitationPresent()).isFalse();
        assertThat(new EvaluationCaseScorer().score(evaluationCase, "[证据: fixture:test]").evidenceCitationPresent())
                .isTrue();
    }
}
