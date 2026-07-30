package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 金标断言匹配：短断言子串、长断言 token 交集。 */
class RagGoldAssertionMatcherTest {

    private final RagGoldAssertionMatcher matcher = new RagGoldAssertionMatcher();

    @Test
    void shortAssertionUsesSubstringMatch() {
        String answer = matcher.normalize("KI-1405 已在 1.4.2 热修中修复");
        assertThat(matcher.matches(answer, "KI-1405")).isTrue();
        assertThat(matcher.matches(answer, "已修复")).isFalse();
    }

    @Test
    void longAssertionMatchesParaphraseWithTokenOverlap() {
        String assertion = "匹配等太久了会强制重置分配";
        String answer = matcher.normalize(
                "玩家反馈匹配等待过久，系统会在超时后强制重置房间分配逻辑");
        assertThat(matcher.matches(answer, assertion)).isTrue();
    }

    @Test
    void longAssertionRejectsUnrelatedAnswer() {
        String answer = matcher.normalize("本次活动奖励翻倍，与匹配无关");
        assertThat(matcher.matches(answer, "匹配等太久了会强制重置分配")).isFalse();
    }

    @Test
    void dev031StyleLongAssertionMatchesPartialWording() {
        String assertion = "本模块为组织级游戏 Workspace 提供统一数据口径，不得用于跨游戏身份识别";
        String answer = matcher.normalize(
                "该模块面向组织级 Workspace 统一数据口径，不支持跨游戏身份混用");
        assertThat(matcher.matches(answer, assertion)).isTrue();
    }
}
