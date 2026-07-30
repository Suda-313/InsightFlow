package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 平台 L2 表达分类器是纯函数；每条反馈必有主标签，零命中回退 expr_other，同分标记 mixed。
 */
class ExpressionClassifierTest {

    private ExpressionClassifier realClassifier() {
        ExpressionRulesLoader loader = new ExpressionRulesLoader();
        loader.load();
        return new ExpressionClassifier(loader.rules());
    }

    /** 命中建议类关键词应返回 expr_suggestion，confidence=1.0，非 mixed。 */
    @Test
    void classifiesSuggestion() {
        ExpressionClassification result = realClassifier().classify("希望优化一下匹配速度");

        assertThat(result.canonicalKey()).isEqualTo("expr_suggestion");
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.mixedExpression()).isFalse();
    }

    /** 命中吐槽类关键词应返回 expr_complaint。 */
    @Test
    void classifiesComplaint() {
        ExpressionClassification result = realClassifier().classify("这游戏太垃圾了，退游");

        assertThat(result.canonicalKey()).isEqualTo("expr_complaint");
    }

    /** 命中好评类关键词应返回 expr_praise。 */
    @Test
    void classifiesPraise() {
        ExpressionClassification result = realClassifier().classify("好玩，强烈推荐给朋友");

        assertThat(result.canonicalKey()).isEqualTo("expr_praise");
    }

    /** 客观叙述类关键词应返回 expr_neutral（体验分享）。 */
    @Test
    void classifiesNeutral() {
        ExpressionClassification result = realClassifier().classify("玩了50个小时，总体来说还行");

        assertThat(result.canonicalKey()).isEqualTo("expr_neutral");
    }

    /** 空文本或纯表情等零命中场景必须回退 expr_other，且 confidence=1.0（确定为其他而非不确定）。 */
    @Test
    void returnsOtherWhenNoRuleMatches() {
        ExpressionClassification result = realClassifier().classify("233333");

        assertThat(result.canonicalKey()).isEqualTo(ExpressionDefaults.EXPR_OTHER_KEY);
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.mixedExpression()).isFalse();
    }

    /** null / 空白文本同样回退 expr_other，不抛异常。 */
    @Test
    void returnsOtherForBlankText() {
        assertThat(realClassifier().classify(null).canonicalKey()).isEqualTo(ExpressionDefaults.EXPR_OTHER_KEY);
        assertThat(realClassifier().classify("   ").canonicalKey()).isEqualTo(ExpressionDefaults.EXPR_OTHER_KEY);
    }

    /** 同 priority 且同 hits 时应标记 mixedExpression=true，confidence=0.5，但仍以 priority 更高者为 primary。 */
    @Test
    void marksMixedOnPriorityHitsTie() {
        ExpressionRule high = new ExpressionRule("expr_suggestion", "建议/诉求", 50, List.of("希望"), List.of());
        ExpressionRule low = new ExpressionRule("expr_complaint", "吐槽/不满", 50, List.of("差"), List.of());
        ExpressionClassifier classifier = new ExpressionClassifier(List.of(high, low));

        ExpressionClassification result = classifier.classify("希望 差");

        assertThat(result.canonicalKey()).isEqualTo("expr_suggestion");
        assertThat(result.mixedExpression()).isTrue();
        assertThat(result.confidence()).isEqualTo(0.5);
    }

    /** 命中排除词的规则应被排除在候选之外。 */
    @Test
    void excludesByExcludePattern() {
        ExpressionRule rule = new ExpressionRule("expr_suggestion", "建议/诉求", 10,
                List.of("希望"), List.of("充值"));
        ExpressionClassifier classifier = new ExpressionClassifier(List.of(rule));

        ExpressionClassification result = classifier.classify("充值后希望优化一下");

        assertThat(result.canonicalKey()).isEqualTo(ExpressionDefaults.EXPR_OTHER_KEY);
    }
}
