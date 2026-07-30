package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** RagGoldDataset 状态机：草稿可发布，发布后不可变。 */
class RagGoldDatasetTest {

    @Test
    void createsDraftDatasetWithPublicId() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");

        assertThat(dataset.getPublicId()).isNotNull();
        assertThat(dataset.getWorkspaceId()).isEqualTo(7L);
        assertThat(dataset.getOrganizationId()).isEqualTo(3L);
        assertThat(dataset.getStatus()).isEqualTo(RagGoldDatasetStatus.DRAFT);
        assertThat(dataset.isMutable()).isTrue();
        assertThat(dataset.isRunnable()).isFalse();
    }

    @Test
    void publishesDraftWithChecksum() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");

        dataset.publish("abc123");

        assertThat(dataset.getStatus()).isEqualTo(RagGoldDatasetStatus.PUBLISHED);
        assertThat(dataset.getChecksum()).isEqualTo("abc123");
        assertThat(dataset.getPublishedAt()).isNotNull();
        assertThat(dataset.isMutable()).isFalse();
        assertThat(dataset.isRunnable()).isTrue();
    }

    @Test
    void freezesPublishedDataset() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.FROZEN, "corpus:v1");
        dataset.publish("abc123");

        dataset.freeze();

        assertThat(dataset.getStatus()).isEqualTo(RagGoldDatasetStatus.FROZEN);
        assertThat(dataset.getFrozenAt()).isNotNull();
        assertThat(dataset.isRunnable()).isTrue();
    }

    @Test
    void rejectsPublishWithoutChecksum() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.DEVELOPMENT, "corpus:v1");

        assertThatThrownBy(() -> dataset.publish(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsFreezeFromDraft() {
        RagGoldDataset dataset = RagGoldDataset.createDraft(
                7L, 3L, "ops-rag", "v1", RagGoldDatasetSplit.FROZEN, "corpus:v1");

        assertThatThrownBy(dataset::freeze)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已发布");
    }
}
