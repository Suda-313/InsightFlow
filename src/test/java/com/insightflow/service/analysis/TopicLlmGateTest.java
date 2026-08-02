package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pack LLM Topic Skill 门控：L2×文本长度组合决定是否值得调用模型。 */
class TopicLlmGateTest {

    @Test
    void invokesForComplaintWithEnoughText() {
        ExpressionClassification expression = new ExpressionClassification("expr_complaint", 1.0, false);

        assertThat(TopicLlmGate.shouldInvokeLlm(expression, "这个匹配系统真的太慢了受不了啊", 15)).isTrue();
    }

    @Test
    void invokesForSuggestionWithEnoughText() {
        ExpressionClassification expression = new ExpressionClassification("expr_suggestion", 1.0, false);

        assertThat(TopicLlmGate.shouldInvokeLlm(expression, "希望官方能优化一下组队体验谢谢", 15)).isTrue();
    }

    @Test
    void skipsShortTextEvenForComplaint() {
        ExpressionClassification expression = new ExpressionClassification("expr_complaint", 1.0, false);

        assertThat(TopicLlmGate.shouldInvokeLlm(expression, "太坑了", 15)).isFalse();
    }

    @Test
    void skipsPraiseRegardlessOfLength() {
        ExpressionClassification expression = new ExpressionClassification("expr_praise", 1.0, false);

        assertThat(TopicLlmGate.shouldInvokeLlm(expression, "画面真香，强烈推荐大家来玩", 15)).isFalse();
    }

    @Test
    void skipsNeutralAndOther() {
        ExpressionClassification neutral = new ExpressionClassification("expr_neutral", 1.0, false);
        ExpressionClassification other = new ExpressionClassification("expr_other", 1.0, false);

        assertThat(TopicLlmGate.shouldInvokeLlm(neutral, "玩了五十小时总体来说还行", 15)).isFalse();
        assertThat(TopicLlmGate.shouldInvokeLlm(other, "？？？", 15)).isFalse();
    }
}
