package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.KnowledgeDocumentVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P4：词法索引文本与 neighbor embed 辅助类测试。 */
class KnowledgeChunkIndexTextTest {

    @Test
    void lexicalIndexIncludesPreambleSectionHeading() {
        KnowledgeDocument document = sampleDocument("1.3-版本更新说明");
        KnowledgeDocumentVersion version = sampleVersion(3);
        KnowledgeChunker.ChunkDraft draft = new KnowledgeChunker.ChunkDraft(
                1, "> 历史版本说明。", 12, KnowledgeChunker.PREAMBLE_SECTION_HEADING);

        String lexical = KnowledgeLexicalIndexText.forChunk(document, version, draft);

        assertThat(lexical).contains("1.3-版本更新说明");
        assertThat(lexical).contains(KnowledgeChunker.PREAMBLE_SECTION_HEADING);
        assertThat(lexical).contains("历史版本说明");
    }

    @Test
    void embedNeighborContextDisabledWhenMaxZero() {
        List<KnowledgeChunker.ChunkDraft> drafts = List.of(
                new KnowledgeChunker.ChunkDraft(1, "第一段尾部ABCDEF", 10, "发布信息"),
                new KnowledgeChunker.ChunkDraft(2, "第二段开头HIJKLMN", 10, "发布信息"),
                new KnowledgeChunker.ChunkDraft(3, "第三段", 3, "主要调整"));

        String middle = KnowledgeEmbedNeighborContext.augment(drafts, 1, "body-2");

        assertThat(middle).isEqualTo("body-2");
        assertThat(middle).doesNotContain("[上文:");
        assertThat(middle).doesNotContain("[下文:");
    }

    private static KnowledgeDocument sampleDocument(String title) {
        return KnowledgeDocument.organizationCommon(1L, KnowledgeDocumentType.RELEASE_NOTE, title);
    }

    private static KnowledgeDocumentVersion sampleVersion(int versionNo) {
        return KnowledgeDocumentVersion.pending(
                1L, versionNo, "k/v" + versionNo, "c", "note.md", "text/markdown", 10L,
                new KnowledgeDocumentVersion.VersionMetadata(null, null, null, null, null, null));
    }
}
