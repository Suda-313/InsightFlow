package com.insightflow.knowledge;

/**
 * 构造送入精排模型的候选文本。
 *
 * <p>必须包含标题、类型、版本、生效窗口、章节与正文，使 Cross-encoder 能区分
 * 同文档不同版本或不同章节；不拼接 Workspace/组织标识。</p>
 */
public final class KnowledgeRerankDocumentText {

    private static final int MAX_BODY_CHARACTERS = 1200;

    private KnowledgeRerankDocumentText() {
    }

    /** 从检索候选格式化精排输入；字段缺失时用占位符，避免空文档导致 API 400。 */
    public static String forCandidate(KnowledgeVectorStore.SearchCandidate candidate) {
        StringBuilder text = new StringBuilder();
        appendLine(text, "title", nullToDash(candidate.title()));
        appendLine(text, "type", nullToDash(candidate.documentType()));
        appendLine(text, "version", "v" + candidate.versionNo());
        appendLine(text, "effective", nullToDash(candidate.effectiveWindow()));
        appendLine(text, "section", nullToDash(candidate.sectionHeading()));
        appendLine(text, "content", truncateBody(candidate.content()));
        return text.toString().trim();
    }

    private static void appendLine(StringBuilder text, String label, String value) {
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(label).append(": ").append(value);
    }

    private static String truncateBody(String content) {
        if (content == null || content.isBlank()) {
            return "-";
        }
        String trimmed = content.trim();
        if (trimmed.length() <= MAX_BODY_CHARACTERS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_BODY_CHARACTERS);
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
