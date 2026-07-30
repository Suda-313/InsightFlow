package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 加载真实 classpath 首包 game-chaoziran:v2，校验身份、topic_general 强制存在与排序。 */
class TopicPackLoaderTest {

    @Test
    void loadsPackIdentityAndTopicCatalog() {
        TopicPackLoader loader = new TopicPackLoader("game-chaoziran");

        loader.load();

        assertThat(loader.packId()).isEqualTo("game-chaoziran");
        assertThat(loader.packVersion()).isEqualTo("game-chaoziran:v2");
        assertThat(loader.displayName()).isNotBlank();
        // 平台强制约束：Pack 必须含 topic_general 兜底议题，且排序在目录最后。
        assertThat(loader.topics()).extracting(TopicPackTopic::canonicalKey)
                .last().isEqualTo(TopicPackDefaults.TOPIC_GENERAL_KEY);
        assertThat(loader.topics())
                .anySatisfy(topic -> {
                    assertThat(topic.canonicalKey()).isEqualTo(TopicPackDefaults.TOPIC_GENERAL_KEY);
                    assertThat(topic.alertEligible()).isFalse();
                });
    }

    /** 可行动议题（稳定性/网络/付费/社交/客服）应标记 alert_eligible=true，供未来 EWMA 告警筛选。 */
    @Test
    void marksActionableTopicsAsAlertEligible() {
        TopicPackLoader loader = new TopicPackLoader("game-chaoziran");

        loader.load();

        assertThat(loader.topics().stream()
                .filter(TopicPackTopic::alertEligible)
                .map(TopicPackTopic::canonicalKey))
                .containsExactlyInAnyOrder("topic_stability", "topic_network", "topic_payment",
                        "topic_social", "topic_service");
    }

    /** topic-rules.toml 同样应完整加载，供未来切换为生产分类规则源时复用。 */
    @Test
    void loadsTopicRules() {
        TopicPackLoader loader = new TopicPackLoader("game-chaoziran");

        loader.load();

        assertThat(loader.rules()).isNotEmpty();
        assertThat(loader.rules()).extracting(IssueRule::canonicalKey).contains("topic_network", "topic_stability");
    }

    @Test
    void defaultsTopicLlmSkillDisabled() {
        TopicPackLoader loader = new TopicPackLoader("game-chaoziran");

        loader.load();

        assertThat(loader.topicLlmSkillEnabled()).isFalse();
    }
}
