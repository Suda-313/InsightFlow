package com.insightflow.knowledge;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    /**
     * 确定性标识符补召回：在可见 published chunk 上对 content/lexical_text/title 做 ILIKE 精确包含匹配。
     *
     * @param identifier 已规范化的运营编号（如 KI-1301），不得来自用户自由 SQL
     * @param assignedScore 并入候选池时使用的 RRF 可比分数
     */
    KnowledgeSearchResult searchByExactIdentifier(
            Long organizationId,
            Long workspaceId,
            String identifier,
            int limit,
            double assignedScore);

    /**
     * Small-to-big：按命中 chunk 加载同版本、同 section_heading 的全部切片，供展示层合并。
     * 须复用 published + Workspace 可见性约束，不得跨组织读取。
     */
    default Map<UUID, List<KnowledgeSectionChunkSlice>> loadSectionChunksBatch(
            Long organizationId, Long workspaceId, List<SearchCandidate> anchorHits) {
        if (anchorHits == null || anchorHits.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<KnowledgeSectionChunkSlice>> result = new java.util.HashMap<>();
        for (SearchCandidate hit : anchorHits) {
            result.put(hit.chunkId(), loadSectionChunks(organizationId, workspaceId, hit.versionId(), hit.chunkId()));
        }
        return result;
    }

    /** 单条命中切片的 section 成员；无可见数据时返回空列表。 */
    List<KnowledgeSectionChunkSlice> loadSectionChunks(
            Long organizationId, Long workspaceId, UUID versionId, UUID anchorChunkId);

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
