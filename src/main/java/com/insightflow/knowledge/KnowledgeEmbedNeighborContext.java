package com.insightflow.knowledge;

import java.util.List;

/**
 * 为向量嵌入拼接相邻 chunk 的短上下文（Phase P4）。
 *
 * <p>仅作用于 embedding 输入，不改变 {@code knowledge_chunk.content} 展示正文；帮助窗口切分边界上的
 * 问答在语义召回时感知前后句，而不把 neighbor 持久化进 FTS 以免噪声放大。</p>
 */
final class KnowledgeEmbedNeighborContext {

    /** 相邻 chunk 各取尾部/头部最多字符数；0 表示关闭 neighbor embed（Phase 1 消融）。 */
    static final int MAX_NEIGHBOR_CHARACTERS = 0;

    private KnowledgeEmbedNeighborContext() {
    }

    /**
     * 在已有 embed 前缀文本后追加 {@code [上文:…]} / {@code [下文:…]} 标记。
     *
     * @param drafts 同一版本内按 chunk_no 排序的切片草稿
     * @param index  当前切片在 drafts 中的下标
     * @param embedText 已含文档/版本/章节前缀的嵌入文本
     */
    static String augment(List<KnowledgeChunker.ChunkDraft> drafts, int index, String embedText) {
        if (MAX_NEIGHBOR_CHARACTERS <= 0) {
            return embedText;
        }
        StringBuilder text = new StringBuilder(embedText);
        if (index > 0) {
            String previous = tail(drafts.get(index - 1).content(), MAX_NEIGHBOR_CHARACTERS);
            if (!previous.isBlank()) {
                text.append("\n[上文: ").append(previous).append(']');
            }
        }
        if (index + 1 < drafts.size()) {
            String next = head(drafts.get(index + 1).content(), MAX_NEIGHBOR_CHARACTERS);
            if (!next.isBlank()) {
                text.append("\n[下文: ").append(next).append(']');
            }
        }
        return text.toString();
    }

    private static String tail(String content, int maxCharacters) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.length() <= maxCharacters) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - maxCharacters);
    }

    private static String head(String content, int maxCharacters) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.length() <= maxCharacters) {
            return trimmed;
        }
        return trimmed.substring(0, maxCharacters);
    }
}
