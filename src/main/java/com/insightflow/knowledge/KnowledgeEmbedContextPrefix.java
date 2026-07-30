package com.insightflow.knowledge;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentVersion;

/**
 * 为向量嵌入拼接文档/版本/章节上下文（Phase R1.2）。
 *
 * <p>嵌入文本使用带前缀的完整上下文，以提升跨文档检索时的语义区分度；{@code knowledge_chunk.content}
 * 仍保存正文供 FTS 片段展示与 Agent 引用，两者分离是刻意设计——展示不必重复标题行，
 * 但向量必须感知文档归属。改前缀或 Chunker 后须重新发布各版本以 re-embed。</p>
 */
final class KnowledgeEmbedContextPrefix {

    private KnowledgeEmbedContextPrefix() {
    }

    /**
     * 构造送入 embedding 模型的文本。
     *
     * <p>格式：{@code [标题 | DOCUMENT_TYPE | vN]}，若有章节则追加 {@code ## 章节}，再接正文。</p>
     */
    static String forEmbedding(
            KnowledgeDocument document, KnowledgeDocumentVersion version, KnowledgeChunker.ChunkDraft draft) {
        StringBuilder text = new StringBuilder();
        text.append('[')
                .append(document.getTitle())
                .append(" | ")
                .append(document.getDocumentType())
                .append(" | v")
                .append(version.getVersionNo());
        appendEffectiveWindow(text, version);
        if (version.getOwner() != null && !version.getOwner().isBlank()) {
            text.append(" | owner:").append(version.getOwner());
        }
        text.append("]\n");
        if (version.getFactBoundary() != null && !version.getFactBoundary().isBlank()) {
            text.append("事实边界: ").append(version.getFactBoundary()).append('\n');
        }
        if (draft.sectionHeading() != null && !draft.sectionHeading().isBlank()) {
            text.append("## ").append(draft.sectionHeading()).append('\n');
        }
        text.append(draft.content());
        return text.toString();
    }

    /** 适用窗口写入 embed 前缀，帮助跨版本调查时区分时效重叠的语料。 */
    private static void appendEffectiveWindow(StringBuilder text, KnowledgeDocumentVersion version) {
        if (version.getEffectiveFrom() == null && version.getEffectiveTo() == null) {
            return;
        }
        text.append(" | ");
        if (version.getEffectiveFrom() != null) {
            text.append(version.getEffectiveFrom());
        } else {
            text.append("*");
        }
        text.append('~');
        if (version.getEffectiveTo() != null) {
            text.append(version.getEffectiveTo());
        } else {
            text.append("*");
        }
    }
}
