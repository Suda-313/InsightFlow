package com.insightflow.knowledge;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentVersion;

/**
 * 发布时为每个 chunk 构建词法检索可见文本。
 *
 * <p>标题、文档类型、版本号与章节标题必须进入 FTS，避免它们只存在于 embedding 前缀而无法被
 * {@code websearch_to_tsquery} 召回；正文仍单独保留在 {@code content} 供展示。</p>
 */
public final class KnowledgeLexicalIndexText {

    private KnowledgeLexicalIndexText() {
    }

    /**
     * 拼接受控词法字段；不包含 Workspace/组织标识，隔离仍由 SQL 可见性 CTE 保证。
     */
    public static String forChunk(
            KnowledgeDocument document,
            KnowledgeDocumentVersion version,
            KnowledgeChunker.ChunkDraft draft) {
        StringBuilder text = new StringBuilder();
        appendToken(text, document.getTitle());
        appendToken(text, document.getDocumentType().name());
        appendToken(text, "v" + version.getVersionNo());
        if (draft.sectionHeading() != null && !draft.sectionHeading().isBlank()) {
            appendToken(text, draft.sectionHeading());
        }
        appendToken(text, draft.content());
        return text.toString().trim();
    }

    private static void appendToken(StringBuilder text, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(token.trim());
    }
}
