package com.insightflow.evaluation.rag.gold.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 将 seed 中的 {@code document_ref + version_no + chunk_no} 解析为语料 manifest 中的公开 UUID。
 *
 * <p>导入时 fail-fast：任一 evidence 无法命中 manifest 条目即抛错并携带 {@code case_key}，
 * 避免静默写入错误证据指针。</p>
 */
public class RagGoldCorpusManifestResolver {

    private final Map<String, Map<Integer, VersionEntry>> byDocumentRef;

    public RagGoldCorpusManifestResolver(Path manifestPath, ObjectMapper objectMapper) throws IOException {
        JsonNode root = objectMapper.readTree(manifestPath.toFile());
        this.byDocumentRef = indexManifest(root);
    }

    /** 解析单条 seed evidence；未命中时抛出带 case_key 上下文的异常。 */
    public ResolvedEvidence resolve(String caseKey, RagGoldSeedFile.EvidenceSeed evidence) {
        Map<Integer, VersionEntry> versions = byDocumentRef.get(evidence.documentRef());
        if (versions == null) {
            throw new IllegalArgumentException(
                    "manifest 未找到 document_ref: " + evidence.documentRef() + " (case_key=" + caseKey + ")");
        }
        VersionEntry version = versions.get(evidence.versionNo());
        if (version == null) {
            throw new IllegalArgumentException(
                    "manifest 未找到 version_no="
                            + evidence.versionNo()
                            + " for document_ref="
                            + evidence.documentRef()
                            + " (case_key="
                            + caseKey
                            + ")");
        }
        ChunkEntry chunk = version.chunks().get(evidence.chunkNo());
        if (chunk == null) {
            throw new IllegalArgumentException(
                    "manifest 未找到 chunk_no="
                            + evidence.chunkNo()
                            + " for document_ref="
                            + evidence.documentRef()
                            + " v"
                            + evidence.versionNo()
                            + " (case_key="
                            + caseKey
                            + ")");
        }
        return new ResolvedEvidence(
                evidence.granularity(),
                version.documentPublicId(),
                version.versionPublicId(),
                chunk.chunkPublicId());
    }

    public boolean hasDocumentRef(String documentRef) {
        return byDocumentRef.containsKey(documentRef);
    }

    public int documentCount() {
        return byDocumentRef.size();
    }

    private static Map<String, Map<Integer, VersionEntry>> indexManifest(JsonNode root) {
        Map<String, Map<Integer, VersionEntry>> index = new HashMap<>();
        JsonNode documents = root.get("documents");
        if (documents == null || !documents.isArray()) {
            throw new IllegalArgumentException("corpus manifest 缺少 documents 数组");
        }
        for (JsonNode document : documents) {
            String documentRef = requiredText(document, "document_ref");
            UUID documentPublicId = UUID.fromString(requiredText(document, "document_id"));
            Map<Integer, VersionEntry> versions = new HashMap<>();
            JsonNode versionNodes = document.get("versions");
            if (versionNodes == null || !versionNodes.isArray()) {
                throw new IllegalArgumentException("document 缺少 versions: " + documentRef);
            }
            for (JsonNode versionNode : versionNodes) {
                int versionNo = versionNode.get("version_no").asInt();
                UUID versionPublicId = UUID.fromString(requiredText(versionNode, "version_id"));
                Map<Integer, ChunkEntry> chunks = new HashMap<>();
                JsonNode chunkNodes = versionNode.get("chunks");
                if (chunkNodes != null && chunkNodes.isArray()) {
                    for (JsonNode chunkNode : chunkNodes) {
                        int chunkNo = chunkNode.get("chunk_no").asInt();
                        UUID chunkPublicId = UUID.fromString(requiredText(chunkNode, "chunk_id"));
                        chunks.put(chunkNo, new ChunkEntry(chunkPublicId));
                    }
                }
                versions.put(versionNo, new VersionEntry(documentPublicId, versionPublicId, chunks));
            }
            index.put(documentRef, versions);
        }
        return index;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException("manifest 字段缺失: " + field);
        }
        return value.asText();
    }

    /** 解析后的 UUID 三元组，供 CommandService 写入 evidence 表。 */
    public record ResolvedEvidence(
            String granularity,
            UUID documentPublicId,
            UUID versionPublicId,
            UUID chunkPublicId) {
    }

    private record VersionEntry(UUID documentPublicId, UUID versionPublicId, Map<Integer, ChunkEntry> chunks) {
    }

    private record ChunkEntry(UUID chunkPublicId) {
    }
}
