package com.insightflow.knowledge;

import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.KnowledgeVersionStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 将当前 Workspace 可见知识文档失效、删除旧版本后，从 {@code docs/knowledge-sources} 重新上传并发布。
 *
 * <p>保留文档 public_id 与标题/类型，使 {@code document_ref} 与金标 manifest 解析口径一致；
 * 新版本会重新切片并写入 {@code section_heading}/{@code lexical_text}。</p>
 */
@Service
public class KnowledgeCorpusRepublishService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCorpusRepublishService.class);
    private static final Path SOURCE_DIR = Path.of("docs", "knowledge-sources");

    private final KnowledgeDocumentService documents;
    private final KnowledgePublishingService publishing;

    public KnowledgeCorpusRepublishService(
            KnowledgeDocumentService documents, KnowledgePublishingService publishing) {
        this.documents = documents;
        this.publishing = publishing;
    }

    /** 逐文档重发布；单文档失败不中断，便于批量运维后汇总。 */
    public List<RepublishDocumentResult> republishAll(UUID workspacePublicId) {
        List<KnowledgeDocumentService.DocumentView> views = documents.list(workspacePublicId);
        List<RepublishDocumentResult> results = new ArrayList<>();
        for (KnowledgeDocumentService.DocumentView view : views) {
            results.add(republishOne(workspacePublicId, view));
        }
        return results;
    }

    RepublishDocumentResult republishOne(UUID workspacePublicId, KnowledgeDocumentService.DocumentView view) {
        UUID documentId = view.document().getPublicId();
        String title = view.document().getTitle();
        try {
            String sourceName = resolveSourceName(view);
            Path sourceFile = SOURCE_DIR.resolve(sourceName);
            if (!Files.isRegularFile(sourceFile)) {
                throw new IllegalStateException("语料文件不存在: " + sourceFile.toAbsolutePath());
            }

            expireAndDeleteVersions(workspacePublicId, documentId);

            KnowledgeDocumentVersion pending = documents.uploadVersion(
                    workspacePublicId,
                    documentId,
                    new DiskMultipartFile(sourceFile, contentType(sourceName)),
                    null);
            KnowledgeDocumentVersion published = publishing.publish(
                    workspacePublicId, documentId, pending.getPublicId(), false);

            log.info(
                    "KNOWLEDGE_REPUBLISH document_id={} title={} version_no={} source={}",
                    documentId,
                    title,
                    published.getVersionNo(),
                    sourceName);
            return new RepublishDocumentResult(documentId, title, sourceName, published.getVersionNo(), null);
        } catch (Exception exception) {
            log.error("KNOWLEDGE_REPUBLISH failed document_id={} title={}", documentId, title, exception);
            return new RepublishDocumentResult(documentId, title, null, 0, exception.getMessage());
        }
    }

    private void expireAndDeleteVersions(UUID workspacePublicId, UUID documentId) {
        for (KnowledgeDocumentVersion version : findVersions(workspacePublicId, documentId)) {
            if (version.getStatus() == KnowledgeVersionStatus.PUBLISHED) {
                documents.expire(workspacePublicId, documentId, version.getPublicId());
            }
        }
        for (KnowledgeDocumentVersion version : findVersions(workspacePublicId, documentId)) {
            if (version.getStatus() == KnowledgeVersionStatus.PENDING_REVIEW
                    || version.getStatus() == KnowledgeVersionStatus.EXPIRED) {
                documents.delete(workspacePublicId, documentId, version.getPublicId());
            }
        }
    }

    private List<KnowledgeDocumentVersion> findVersions(UUID workspacePublicId, UUID documentId) {
        return documents.list(workspacePublicId).stream()
                .filter(view -> view.document().getPublicId().equals(documentId))
                .findFirst()
                .map(KnowledgeDocumentService.DocumentView::versions)
                .orElse(List.of());
    }

    /** 优先用最近版本的 source_name；缺失时按标题匹配 knowledge-sources 文件名。 */
    private String resolveSourceName(KnowledgeDocumentService.DocumentView view) {
        return view.versions().stream()
                .map(KnowledgeDocumentVersion::getSourceName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElseGet(() -> {
                    try {
                        return findSourceByTitle(view.document().getTitle());
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException("无法匹配语料文件: " + view.document().getTitle(), exception);
                    }
                });
    }

    private String findSourceByTitle(String title) throws java.io.IOException {
        if (!Files.isDirectory(SOURCE_DIR)) {
            throw new IllegalStateException("语料目录不存在: " + SOURCE_DIR.toAbsolutePath());
        }
        try (var stream = Files.list(SOURCE_DIR)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.contains(title) || name.replace("超自然行动组-", "").contains(title))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("无法为标题匹配语料文件: " + title));
        }
    }

    private String contentType(String sourceName) {
        return sourceName.toLowerCase().endsWith(".txt") ? "text/plain" : "text/markdown";
    }

    public record RepublishDocumentResult(
            UUID documentPublicId,
            String title,
            String sourceName,
            int publishedVersionNo,
            String errorMessage) {

        public boolean succeeded() {
            return errorMessage == null;
        }
    }
}
