package com.insightflow.knowledge;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.KnowledgeVersionStatus;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.KnowledgeDocumentRepository;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import com.insightflow.service.WorkspaceService;
import com.insightflow.storage.KnowledgeObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 待审核知识版本的受控发布用例。 */
@Service
@Transactional(readOnly = true)
public class KnowledgePublishingService {
    private final WorkspaceService workspaceService; private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentVersionRepository versions; private final KnowledgeObjectStorage storage;
    private final KnowledgeChunker chunker; private final KnowledgeEmbeddingGateway embeddings; private final KnowledgeVectorStore vectors;

    /** 所有发布副作用显式注入，发布链路不依赖模型自由工具调用。 */
    public KnowledgePublishingService(WorkspaceService workspaceService, KnowledgeDocumentRepository documents,
            KnowledgeDocumentVersionRepository versions, KnowledgeObjectStorage storage, KnowledgeChunker chunker,
            KnowledgeEmbeddingGateway embeddings, KnowledgeVectorStore vectors) {
        this.workspaceService = workspaceService; this.documents = documents; this.versions = versions; this.storage = storage;
        this.chunker = chunker; this.embeddings = embeddings; this.vectors = vectors;
    }

    /** 发布前校验当前 Workspace 的组织范围，旧已发布版本在相同事务内失效。 */
    @Transactional
    public KnowledgeDocumentVersion publish(UUID workspacePublicId, UUID documentPublicId, UUID versionPublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        KnowledgeDocument document = documents.findByPublicId(documentPublicId).orElseThrow();
        if (!document.getOrganizationId().equals(workspace.getOrganizationId())
                || (document.getTargetWorkspaceId() != null && !document.getTargetWorkspaceId().equals(workspace.getId()))) {
            throw new IllegalArgumentException("知识文档不属于当前工作区可见范围");
        }
        KnowledgeDocumentVersion version = versions.findByPublicIdAndDocumentId(versionPublicId, document.getId()).orElseThrow();
        // 状态机先于对象读取和嵌入调用执行，避免失效/删除版本消耗外部存储与模型资源。
        if (version.getStatus() != KnowledgeVersionStatus.PENDING_REVIEW) {
            throw new IllegalStateException("只有待审核知识版本可以发布");
        }
        List<KnowledgeChunker.ChunkDraft> drafts = chunker.chunk(read(version));
        List<List<Double>> vectorsResult = embeddings.embed(drafts.stream().map(KnowledgeChunker.ChunkDraft::content).toList());
        if (vectorsResult.size() != drafts.size()) throw new IllegalStateException("嵌入结果与知识切片数量不一致");
        versions.findByDocumentIdAndStatus(document.getId(), KnowledgeVersionStatus.PUBLISHED).forEach(old -> old.expire(OffsetDateTime.now()));
        version.publish(OffsetDateTime.now()); versions.save(version);
        vectors.store(version.getId(), java.util.stream.IntStream.range(0, drafts.size()).mapToObj(i ->
                new KnowledgeVectorStore.EmbeddedChunk(drafts.get(i).chunkNo(), drafts.get(i).content(), drafts.get(i).tokenCount(), vectorsResult.get(i))).toList());
        return version;
    }

    /** 原文只从已受控对象键读取并按 UTF-8 解码，读取失败时事务回滚且版本保持待审核。 */
    private String read(KnowledgeDocumentVersion version) {
        try (var input = storage.open(version.getObjectKey())) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException exception) { throw new IllegalStateException("无法读取知识原文", exception); }
    }
}
