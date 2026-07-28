package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 知识版本状态机测试。
 *
 * <p>文档正文保留在对象存储，版本实体只管理其可检索性和审计时间。测试先锁住单向状态变化，
 * 防止后续 API 直接把失效或删除版本重新暴露给 RAG。</p>
 */
class KnowledgeDocumentVersionTest {

    /**
     * 待审核版本只有在切片和嵌入均成功后才能发布，发布时必须记录稳定审计时间。
     */
    @Test
    void publishesOnlyPendingVersion() {
        KnowledgeDocumentVersion version = KnowledgeDocumentVersion.pending(
                11L, 2, "knowledge/org/doc/v2/source", "checksum", "release.md", "text/markdown", 42L);
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-07-25T00:00:00+08:00");

        version.publish(publishedAt);

        assertThat(version.getStatus()).isEqualTo(KnowledgeVersionStatus.PUBLISHED);
        assertThat(version.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(version.getExpiredAt()).isNull();
    }

    /**
     * 已发布版本只能先失效，不能被再次发布或直接逻辑删除，以免破坏已被回答引用的历史语义。
     */
    @Test
    void preventsInvalidTransitionsFromPublishedVersion() {
        KnowledgeDocumentVersion version = KnowledgeDocumentVersion.pending(
                11L, 1, "knowledge/org/doc/v1/source", "checksum", "release.md", "text/markdown", 42L);
        version.publish(OffsetDateTime.parse("2026-07-25T00:00:00+08:00"));

        assertThatThrownBy(() -> version.publish(OffsetDateTime.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> version.delete(OffsetDateTime.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 失效后的版本仍保留审计记录，但状态不可逆；只有待审核或失效版本可被逻辑删除。
     */
    @Test
    void expiresThenLogicallyDeletesHistoricalVersion() {
        KnowledgeDocumentVersion version = KnowledgeDocumentVersion.pending(
                11L, 1, "knowledge/org/doc/v1/source", "checksum", "release.md", "text/markdown", 42L);
        OffsetDateTime expiredAt = OffsetDateTime.parse("2026-07-25T00:10:00+08:00");
        version.publish(OffsetDateTime.parse("2026-07-25T00:00:00+08:00"));

        version.expire(expiredAt);
        version.delete(expiredAt.plusMinutes(1));

        assertThat(version.getStatus()).isEqualTo(KnowledgeVersionStatus.DELETED);
        assertThat(version.getExpiredAt()).isEqualTo(expiredAt);
        assertThat(version.getDeletedAt()).isEqualTo(expiredAt.plusMinutes(1));
    }
}
