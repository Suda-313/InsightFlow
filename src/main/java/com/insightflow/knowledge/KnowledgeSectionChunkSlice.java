package com.insightflow.knowledge;

/**
 * 同一文档版本内、用于 Small-to-big 展示扩展的 section 成员切片。
 *
 * <p>只含 chunk 序号与正文，不含向量或内部主键；由 {@link KnowledgeVectorStore} 在 Workspace
 * 可见范围内加载，供 {@link KnowledgeEvidenceContextExpander} 合并进 Prompt snippet。</p>
 */
public record KnowledgeSectionChunkSlice(int chunkNo, String content, String sectionHeading) {

    public KnowledgeSectionChunkSlice {
        content = content == null ? "" : content;
        sectionHeading = sectionHeading == null ? "" : sectionHeading;
    }
}
