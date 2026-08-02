package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 超自然 Pack v2 规则应对常见口语反馈有基本召回，而非全部落入 topic_general。 */
class ChaoziranTopicRulesClassifierTest {

    private final RuleFirstIssueClassifier classifier;
    private final IssueTextNormalizer normalizer;

    ChaoziranTopicRulesClassifierTest() {
        TopicPackRegistry registry = new TopicPackRegistry("game-chaoziran");
        registry.load();
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        this.classifier = new RuleFirstIssueClassifier(registry.requireByPackId("game-chaoziran").rules());
        this.normalizer = new IssueTextNormalizer(loader.normalizeMappings());
    }

    @Test
    void classifiesDropRateComplaint() {
        assertThat(classifier.classify(normalize("爆率再高一点就好"))).extracting(Classification::canonicalKey)
                .contains("topic_gameplay");
    }

    @Test
    void classifiesCheatReportSlang() {
        assertThat(classifier.classify(normalize("就是挂太多"))).extracting(Classification::canonicalKey)
                .contains("topic_social");
    }

    @Test
    void classifiesVisualQualityFeedback() {
        assertThat(classifier.classify(normalize("画质很差，完全简圈钱"))).extracting(Classification::canonicalKey)
                .contains("topic_visual");
    }

    @Test
    void classifiesMatchmakingIssue() {
        assertThat(classifier.classify(normalize("怎么老是开始的时候配不到队友呢"))).extracting(Classification::canonicalKey)
                .contains("topic_matchmaking");
    }

    private String normalize(String text) {
        return normalizer.normalize(text);
    }
}
