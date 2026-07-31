package com.insightflow.agent.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.insightflow.knowledge.KnowledgeQueryExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 规则改写器的触发条件、模板与恒等保证。 */
class ContextualQueryRewriterTest {

    private ContextualQueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        rewriter = new ContextualQueryRewriter(new KnowledgeQueryExpander());
    }

    @Test
    void doesNotRewriteSelfContainedQuestionWithSameReference() {
        String message = "登录异常最近为什么暴增？";

        RewriteOutcome outcome = rewriter.rewrite(message, focus("登录异常", "近14天", null));

        assertThat(outcome.triggered()).isFalse();
        assertSame(outcome.original(), outcome.rewritten());
    }

    @Test
    void rewritesPronounFollowUpWithFocusTemplate() {
        ChatSessionFocus focus = focus("登录异常", "近14天", null);

        RewriteOutcome outcome = rewriter.rewrite("它为什么涨", focus);

        assertThat(outcome.triggered()).isTrue();
        assertThat(outcome.rewritten()).isEqualTo("登录异常 近14天为什么涨");
    }

    @Test
    void substitutesInsideReferenceWithCompactAnchor() {
        ChatSessionFocus focus = focus("结算页显示了奖励但背包迟迟不到", null, "1.4");

        RewriteOutcome outcome = rewriter.rewrite("里面提到的关键信息是什么？", focus);

        assertThat(outcome.triggered()).isTrue();
        assertThat(outcome.rewritten()).isEqualTo("1.4 结算页显示了奖励但背包迟迟不到提到的关键信息是什么？");
    }

    @Test
    void capsRewrittenLength() {
        String longTopic = "结算页显示了奖励但背包迟迟不到且客服工单已升级至二线且玩家情绪较为激动需要优先处理";
        ChatSessionFocus focus = focus(longTopic, "近14天", "1.4.2");

        RewriteOutcome outcome = rewriter.rewrite("里面具体是什么？", focus);

        assertThat(outcome.triggered()).isTrue();
        assertThat(outcome.rewritten().length()).isLessThanOrEqualTo(120);
    }

    @Test
    void doesNotRewriteWhenFocusEmpty() {
        String message = "它为什么涨";

        RewriteOutcome outcome = rewriter.rewrite(message, ChatSessionFocus.empty());

        assertThat(outcome.triggered()).isFalse();
        assertSame(message, outcome.rewritten());
    }

    @Test
    void doesNotRewriteWhenMessageAlreadyContainsVersion() {
        String message = "1.4 版本还有什么问题？";

        RewriteOutcome outcome = rewriter.rewrite(message, focus("全量更新", null, "1.4"));

        assertThat(outcome.triggered()).isFalse();
        assertSame(message, outcome.rewritten());
    }

    private ChatSessionFocus focus(String topicKey, String timeWindow, String versionLabel) {
        return ChatSessionFocus.of(topicKey, timeWindow, versionLabel);
    }
}
