package com.insightflow.agent.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.ChatSession;
import com.insightflow.knowledge.KnowledgeQueryExpander;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 焦点抽取与空焦点不覆盖已有值的契约。 */
class ConversationFocusExtractorTest {

    private ConversationFocusExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new ConversationFocusExtractor(new KnowledgeQueryExpander());
    }

    @Test
    void extractsTopicAndTimeWindowFromTrendEvidence() {
        InvestigationPlan plan = new InvestigationPlan(
                InvestigationIntent.TREND_EXPLANATION, List.of(InvestigationToolType.ISSUE_TREND));
        InvestigationResult result = new InvestigationResult(plan, List.of(new InvestigationEvidence(
                "trend:login_issue:last_14_days",
                InvestigationToolType.ISSUE_TREND,
                "主题趋势",
                "来源 issue_metric_bucket；登录异常 最近7天 12 条，前7天 8 条。",
                true)));

        ChatSessionFocus focus = extractor.extract(result, "它为什么涨");

        assertThat(focus.topicKey()).isEqualTo("登录异常");
        assertThat(focus.timeWindow()).isEqualTo("近14天");
    }

    @Test
    void returnsEmptyFocusWhenEvidenceHasNoSlots() {
        InvestigationPlan plan = new InvestigationPlan(
                InvestigationIntent.GENERAL_INQUIRY, List.of(InvestigationToolType.TOPIC_DISTRIBUTION));
        InvestigationResult result = new InvestigationResult(plan, List.of(new InvestigationEvidence(
                "distribution:last_7_days",
                InvestigationToolType.TOPIC_DISTRIBUTION,
                "主题分布",
                "来源 issue_metric_bucket；最近7天没有可用的主题聚合数据。",
                false)));

        ChatSessionFocus focus = extractor.extract(result, "你好");

        assertThat(focus.isEmpty()).isTrue();
    }

    @Test
    void emptyFocusDoesNotOverwriteExistingSessionValues() {
        ChatSession session = ChatSession.create(1L);
        session.updateFocus(ChatSessionFocus.of("登录异常", "近14天", "1.4"));

        session.updateFocus(ChatSessionFocus.empty());

        assertThat(session.getFocusTopicKey()).isEqualTo("登录异常");
        assertThat(session.getFocusTimeWindow()).isEqualTo("近14天");
        assertThat(session.getFocusVersionLabel()).isEqualTo("1.4");
    }

    @Test
    void extractFromTextUsesLastUserTurnForMultiturnEval() {
        List<ContextTurn> turns = List.of(
                new ContextTurn("user", "1.4 全量更新的上线窗口是几点到几点？"),
                new ContextTurn("assistant", "相关文档中有记录。"));

        ChatSessionFocus focus = extractor.extractFromText(turns);

        assertThat(focus.versionLabel()).isEqualTo("1.4");
        assertThat(focus.topicKey()).isEqualTo("全量更新的上线窗口");
    }

    @Test
    void stripsContextFillerSuffixFromTopicKey() {
        List<ContextTurn> turns = List.of(new ContextTurn(
                "user", "玩家问结算页显示了奖励但背包迟迟不到的相关说明是什么？"));

        ChatSessionFocus focus = extractor.extractFromText(turns);

        assertThat(focus.topicKey()).isEqualTo("结算页显示了奖励但背包迟迟不到");
    }
}
