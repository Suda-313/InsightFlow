package com.insightflow.knowledge;

import java.util.List;
import com.insightflow.entity.KnowledgeDocumentType;

/** pgvector 切片写入端口；模型和业务服务都不能直接构造 SQL。 */
public interface KnowledgeVectorStore {

    /** 原子写入某一已发布版本的所有切片与向量。 */
    void store(Long versionId, List<EmbeddedChunk> chunks);

    /** 在数据库内完成组织范围、发布状态、FTS 与 pgvector 召回，调用方只接收受控候选。 */
    List<SearchCandidate> search(Long organizationId, Long workspaceId, String query,
            List<KnowledgeDocumentType> types, List<Double> queryEmbedding, int limit);

    /** 发布物中的单个切片，不含内部主键、原文件或推理过程。 */
    record EmbeddedChunk(int chunkNo, String content, int tokenCount, List<Double> embedding) {
    }

    /** 不含内部主键的检索候选；标题、版本和切片 UUID 可安全成为回答引用。 */
    record SearchCandidate(java.util.UUID documentId, java.util.UUID versionId, int versionNo,
            java.util.UUID chunkId, String title, String content, double score) {
    }
}
