package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insightflow.entity.Workspace;
import org.junit.jupiter.api.Test;

class TopicPackRegistryTest {

    @Test
    void loadsDefaultPackAndListsSummaries() {
        TopicPackRegistry registry = new TopicPackRegistry("game-chaoziran");
        registry.load();

        assertThat(registry.defaultPackId()).isEqualTo("game-chaoziran");
        assertThat(registry.listSummaries()).extracting(TopicPackRegistry.TopicPackSummary::packId)
                .contains("game-chaoziran");
        assertThat(registry.requireByPackId("game-chaoziran").topics())
                .extracting(TopicPackTopic::canonicalKey)
                .contains(TopicPackDefaults.TOPIC_GENERAL_KEY);
    }

    @Test
    void resolveForWorkspaceUsesBindingOrDefault() {
        TopicPackRegistry registry = new TopicPackRegistry("game-chaoziran");
        registry.load();

        Workspace unbound = new Workspace("test", 1L);
        assertThat(registry.resolveForWorkspace(unbound).packId()).isEqualTo("game-chaoziran");

        unbound.bindTopicPack("game-chaoziran");
        assertThat(registry.resolveForWorkspace(unbound).packId()).isEqualTo("game-chaoziran");
    }

    @Test
    void rejectsUnknownPackId() {
        TopicPackRegistry registry = new TopicPackRegistry("game-chaoziran");
        registry.load();

        assertThatThrownBy(() -> registry.requireByPackId("unknown-pack"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
