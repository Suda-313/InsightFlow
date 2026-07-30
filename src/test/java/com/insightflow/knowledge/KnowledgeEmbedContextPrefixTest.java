package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.KnowledgeDocumentVersion;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** embed 前缀必须携带版本元数据，帮助跨版本调查时区分时效与事实边界。 */
class KnowledgeEmbedContextPrefixTest {

    @Test
    void includesEffectiveWindowOwnerAndFactBoundaryInEmbedText() {
        KnowledgeDocument document = KnowledgeDocument.organizationCommon(
                1L, KnowledgeDocumentType.OPERATION_EVENT, "1.4 渠道活动记录");
        KnowledgeDocumentVersion version = KnowledgeDocumentVersion.pending(
                1L, 1, "k/v1", "c", "event.md", "text/markdown", 10L,
                new KnowledgeDocumentVersion.VersionMetadata(
                        "https://example.com/event",
                        OffsetDateTime.parse("2026-07-20T00:00:00+08:00"),
                        OffsetDateTime.parse("2026-07-01T00:00:00+08:00"),
                        OffsetDateTime.parse("2026-07-31T23:59:59+08:00"),
                        "运营A组",
                        "仅覆盖 TapTap 渠道反馈，不含内部工单数据"));

        String text = KnowledgeEmbedContextPrefix.forEmbedding(
                document, version, new KnowledgeChunker.ChunkDraft(1, "正文", 2, "活动详情"));

        assertThat(text).contains("OPERATION_EVENT | v1");
        assertThat(text).contains("2026-07-01T00:00");
        assertThat(text).contains("2026-07-31T23:59:59");
        assertThat(text).contains("owner:运营A组");
        assertThat(text).contains("事实边界: 仅覆盖 TapTap 渠道反馈");
        assertThat(text).contains("## 活动详情");
    }
}
