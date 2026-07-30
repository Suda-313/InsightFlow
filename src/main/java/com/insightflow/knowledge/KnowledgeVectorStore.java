package com.insightflow.knowledge;

import java.util.List;
import com.insightflow.entity.KnowledgeDocumentType;

/** pgvector 切片写入端口；模型和业务服务都不能直接构造 SQL。 */
public interface KnowledgeVectorStore {

    /** 原子写入某一已发布版本的所有切片与向量。 */
    void store(Long versionId, List<EmbeddedChunk> chunks);

    /**
     * 兼容入口：默认使用 {@link KnowledgeSearchOptions#legacyV1}，由 {@link KnowledgeSearchTool} 传入 v2 选项。
     */
    default List<SearchCandidate> search(Long organizationId, Long workspaceId, String query,
            List<KnowledgeDocumentType> types, List<Double> queryEmbedding, int limit) {
        return searchWithOptions(
                organizationId, workspaceId, query, types, queryEmbedding,
                KnowledgeSearchOptions.legacyV1(query, limit))
                .candidates();
    }

    /** 在数据库内完成组织范围、发布状态、FTS 与 pgvector 召回，并返回来源诊断。 */
    KnowledgeSearchResult searchWithOptions(Long organizationId, Long workspaceId, String query,
            List<KnowledgeDocumentType> types, List<Double> queryEmbedding, KnowledgeSearchOptions options);

    /** 发布物中的单个切片，不含内部主键、原文件或推理过程。 */
    record EmbeddedChunk(
            int chunkNo,
            String content,
            int tokenCount,
            List<Double> embedding,
            String sectionHeading,
            String lexicalText) {

        /** 兼容旧调用：无章节/词法文本时由发布层补全。 */
        public EmbeddedChunk(int chunkNo, String content, int tokenCount, List<Double> embedding) {
            this(chunkNo, content, tokenCount, embedding, null, null);
        }
    }

    /**
     * 不含内部主键的检索候选；标题、版本和切片 UUID 可安全成为回答引用。
     * documentType/sectionHeading/effectiveWindow 供精排格式化，不参与向量 SQL 过滤。
     */
    record SearchCandidate(
            java.util.UUID documentId,
            java.util.UUID versionId,
            int versionNo,
            java.util.UUID chunkId,
            String title,
            String content,
            double score,
            String documentType,
            String sectionHeading,
            String effectiveWindow) {

        /** 兼容无精排元数据的构造。 */
        public SearchCandidate(
                java.util.UUID documentId,
                java.util.UUID versionId,
                int versionNo,
                java.util.UUID chunkId,
                String title,
                String content,
                double score) {
            this(documentId, versionId, versionNo, chunkId, title, content, score, null, null, null);
        }
    }
}
