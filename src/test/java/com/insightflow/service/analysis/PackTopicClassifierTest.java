package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** PackTopicClassifier：规则优先，仅 general 子集且门控通过时调用 LLM。 */
class PackTopicClassifierTest {

    private static final String GENERAL_CANDIDATE_TEXT = "整体体验一般但暂时说不出具体哪个方面有问题";

    private final TopicPackRegistry registry = topicPackRegistry();

    private static TopicPackRegistry topicPackRegistry() {
        TopicPackRegistry registry = new TopicPackRegistry("game-chaoziran");
        registry.load();
        return registry;
    }

    @Test
    void keepsRuleHitWithoutCallingLlm() {
        TopicPackTopicLlmSkill llmSkill = mock(TopicPackTopicLlmSkill.class);
        PackTopicClassifier classifier = new PackTopicClassifier(
                llmSkill, new TopicLlmSkillProperties(true, 0.7, 15));
        TopicPackLoader pack = registry.requireByPackId("game-chaoziran");
        RuleFirstIssueClassifier ruleClassifier = new RuleFirstIssueClassifier(pack.rules());

        PackTopicClassifier.PackTopicClassificationOutcome outcome = classifier.classify(
                "爆率再高一点就好",
                new ExpressionClassification("expr_suggestion", 1.0, false),
                pack,
                ruleClassifier);

        assertThat(outcome.classifications()).extracting(Classification::canonicalKey).contains("topic_gameplay");
        assertThat(outcome.llmAttempt()).isNull();
        verify(llmSkill, never()).classify(any(), any());
    }

    @Test
    void upgradesGeneralWhenLlmHighConfidence() {
        TopicPackTopicLlmSkill llmSkill = mock(TopicPackTopicLlmSkill.class);
        when(llmSkill.promptVersion()).thenReturn("pack-topic:v1");
        when(llmSkill.classify(eq(GENERAL_CANDIDATE_TEXT), any(TopicPackLoader.class)))
                .thenReturn(Optional.of(new TopicPackTopicLlmSkill.TopicPackTopicLlmResultDto("topic_matchmaking", 0.92)));
        TopicPackLoader pack = enabledPack();
        PackTopicClassifier classifier = new PackTopicClassifier(
                llmSkill, new TopicLlmSkillProperties(true, 0.7, 15));
        RuleFirstIssueClassifier ruleClassifier = new RuleFirstIssueClassifier(pack.rules());

        PackTopicClassifier.PackTopicClassificationOutcome outcome = classifier.classify(
                GENERAL_CANDIDATE_TEXT,
                new ExpressionClassification("expr_complaint", 1.0, false),
                pack,
                ruleClassifier);

        assertThat(outcome.classifications()).hasSize(1);
        assertThat(outcome.classifications().get(0).canonicalKey()).isEqualTo("topic_matchmaking");
        assertThat(outcome.classifications().get(0).assignmentMethod()).isEqualTo(TopicPackDefaults.ASSIGNMENT_LLM);
        assertThat(outcome.llmAttempt()).isNotNull();
        assertThat(outcome.llmAttempt().accepted()).isTrue();
    }

    @Test
    void keepsGeneralWhenLlmLowConfidence() {
        TopicPackTopicLlmSkill llmSkill = mock(TopicPackTopicLlmSkill.class);
        when(llmSkill.promptVersion()).thenReturn("pack-topic:v1");
        when(llmSkill.classify(any(), any()))
                .thenReturn(Optional.of(new TopicPackTopicLlmSkill.TopicPackTopicLlmResultDto("topic_matchmaking", 0.4)));
        TopicPackLoader pack = enabledPack();
        PackTopicClassifier classifier = new PackTopicClassifier(
                llmSkill, new TopicLlmSkillProperties(true, 0.7, 15));
        RuleFirstIssueClassifier ruleClassifier = new RuleFirstIssueClassifier(pack.rules());

        PackTopicClassifier.PackTopicClassificationOutcome outcome = classifier.classify(
                GENERAL_CANDIDATE_TEXT,
                new ExpressionClassification("expr_complaint", 1.0, false),
                pack,
                ruleClassifier);

        assertThat(outcome.classifications().get(0).canonicalKey()).isEqualTo(TopicPackDefaults.TOPIC_GENERAL_KEY);
        assertThat(outcome.llmAttempt()).isNotNull();
        assertThat(outcome.llmAttempt().accepted()).isFalse();
    }

    @Test
    void skipsLlmWhenGlobalSwitchDisabled() {
        TopicPackTopicLlmSkill llmSkill = mock(TopicPackTopicLlmSkill.class);
        PackTopicClassifier classifier = new PackTopicClassifier(
                llmSkill, new TopicLlmSkillProperties(false, 0.7, 15));
        TopicPackLoader pack = enabledPack();
        RuleFirstIssueClassifier ruleClassifier = new RuleFirstIssueClassifier(pack.rules());

        PackTopicClassifier.PackTopicClassificationOutcome outcome = classifier.classify(
                GENERAL_CANDIDATE_TEXT,
                new ExpressionClassification("expr_complaint", 1.0, false),
                pack,
                ruleClassifier);

        assertThat(outcome.classifications().get(0).canonicalKey()).isEqualTo(TopicPackDefaults.TOPIC_GENERAL_KEY);
        verify(llmSkill, never()).classify(any(), any());
    }

    private TopicPackLoader enabledPack() {
        TopicPackLoader pack = registry.requireByPackId("game-chaoziran");
        // 测试用反射或包可见 setter 不存在；直接构造带 enabled 的 loader 不现实。
        // 使用 mock 包装 pack 的 topicLlmSkillEnabled。
        TopicPackLoader spyPack = mock(TopicPackLoader.class);
        when(spyPack.topics()).thenReturn(pack.topics());
        when(spyPack.rules()).thenReturn(pack.rules());
        when(spyPack.topicLlmSkillEnabled()).thenReturn(true);
        when(spyPack.packId()).thenReturn(pack.packId());
        return spyPack;
    }
}
