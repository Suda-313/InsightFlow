package com.insightflow.knowledge;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Markdown/TXT 的确定性首版切片器。
 *
 * <p>首版按空行保留段落边界，并仅在单段超过窗口时按字符拆分。它不尝试让模型决定边界，
 * 因此同一版本在重建时会产生稳定的 chunk_no，便于 RAG 金标和引用审计。</p>
 */
@Component
public class KnowledgeChunker {

    /** 单个切片最大字符数；由构造参数固定，避免在检索时临时改变已发布版本的切片语义。 */
    private final int maxCharacters;

    /**
     * 创建切片器。
     *
     * @param maxCharacters 正整数窗口；首版使用字符数近似 token，实际 token_count 只作为检索审计统计
     */
    public KnowledgeChunker(@Value("${insightflow.knowledge.chunk-max-characters:1000}") int maxCharacters) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("知识切片窗口必须为正数");
        }
        this.maxCharacters = maxCharacters;
    }

    /**
     * 将非空正文切成连续编号的草稿。
     *
     * <p>连续短段会在不超过窗口时合并，保留原来的空行；长段仅按窗口拆分，不额外插入或丢弃字符。</p>
     *
     * @param content 已通过 UTF-8 与非空校验的 Markdown/TXT 正文
     * @return 从一开始连续编号的可嵌入片段
     */
    public List<ChunkDraft> chunk(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("知识正文不能为空");
        }
        List<String> units = splitLongParagraphs(content.trim());
        List<ChunkDraft> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.isEmpty()) {
                current.append(unit);
            } else if (current.length() + 2 + unit.length() <= maxCharacters) {
                current.append("\n\n").append(unit);
            } else {
                append(chunks, current.toString());
                current.setLength(0);
                current.append(unit);
            }
        }
        if (!current.isEmpty()) {
            append(chunks, current.toString());
        }
        return List.copyOf(chunks);
    }

    /** 将 Markdown 的段落分隔统一为两个换行，避免 Windows 与 Unix 换行导致相同文档切片不同。 */
    private List<String> splitLongParagraphs(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> units = new ArrayList<>();
        for (String paragraph : normalized.split("\\n\\s*\\n")) {
            String trimmed = paragraph.trim();
            for (int start = 0; start < trimmed.length(); start += maxCharacters) {
                units.add(trimmed.substring(start, Math.min(start + maxCharacters, trimmed.length())));
            }
        }
        return units;
    }

    /** 统一计算 chunk_no 和近似 token 统计；正文为空时不会到达此方法。 */
    private void append(List<ChunkDraft> chunks, String content) {
        chunks.add(new ChunkDraft(chunks.size() + 1, content, Math.max(1, content.length())));
    }

    /** 切片持久化前的不可变草稿，不包含内部 ID、对象键或任何模型推理信息。 */
    public record ChunkDraft(int chunkNo, String content, int tokenCount) {
    }
}
