package com.insightflow.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.knowledge.KnowledgeDocumentService;
import com.insightflow.knowledge.KnowledgePublishingService;
import com.insightflow.security.WorkspaceAccessService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 企业知识管理 HTTP 边界。
 *
 * <p>路径中的 Workspace 是每个读写请求的可见范围入口，不是客户端传入目标 Workspace 的通道；
 * Controller 不直接访问仓储或 MinIO，归属、生命周期和对象存储权限均委托给知识用例服务。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/knowledge")
public class KnowledgeDocumentController {

    /** 上传、列表、失效、逻辑删除和原文读取的领域用例。 */
    private final KnowledgeDocumentService documents;

    /** 发布会触发受控切片与嵌入，因此与普通元数据变更分离。 */
    private final KnowledgePublishingService publishing;

    /** 所有知识读写先验证当前 JWT 对路径 Workspace 的实时访问权限。 */
    private final WorkspaceAccessService workspaceAccess;

    /** 构造器显式区分普通管理操作和会调用模型的发布操作。 */
    public KnowledgeDocumentController(
            KnowledgeDocumentService documents,
            KnowledgePublishingService publishing,
            WorkspaceAccessService workspaceAccess) {
        this.documents = documents;
        this.publishing = publishing;
        this.workspaceAccess = workspaceAccess;
    }

    /**
     * 上传只会创建待审核版本，不会自动进入 RAG。
     * scope 只能是组织通用或当前路径 Workspace 专属，拒绝未知值而不是悄悄改变可见范围。
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VersionResponse> upload(
            @PathVariable UUID workspaceId,
            @RequestParam String title,
            @RequestParam KnowledgeDocumentType type,
            @RequestParam(defaultValue = "WORKSPACE") String scope,
            @RequestParam("file") MultipartFile file) {
        workspaceAccess.requireRead(workspaceId);
        KnowledgeDocumentVersion version = documents.upload(workspaceId,
                new KnowledgeDocumentService.UploadCommand(title, type, organizationCommon(scope), file));
        return ResponseEntity.status(HttpStatus.CREATED).body(VersionResponse.from(version));
    }

    /** 列出当前 Workspace 可见的组织通用文档和自身专属文档，绝不列出其他游戏专属资料。 */
    @GetMapping("/documents")
    public List<DocumentResponse> list(@PathVariable UUID workspaceId) {
        workspaceAccess.requireRead(workspaceId);
        return documents.list(workspaceId).stream().map(DocumentResponse::from).toList();
    }

    /** 发布时同步完成切片和嵌入；失败版本由服务层保留为待审核而不是伪装为已发布。 */
    @PostMapping("/documents/{documentId}/versions/{versionId}/publish")
    public VersionResponse publish(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
            @PathVariable UUID versionId) {
        workspaceAccess.requireRead(workspaceId);
        return VersionResponse.from(publishing.publish(workspaceId, documentId, versionId));
    }

    /** 已发布版本失效后停止参与 RAG，但审计历史和引用定位仍被保留。 */
    @PostMapping("/documents/{documentId}/versions/{versionId}/expire")
    public VersionResponse expire(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
            @PathVariable UUID versionId) {
        workspaceAccess.requireRead(workspaceId);
        return VersionResponse.from(documents.expire(workspaceId, documentId, versionId));
    }

    /** 删除为逻辑删除；不物理移除可能已被 AgentRun 证据快照引用的版本。 */
    @DeleteMapping("/documents/{documentId}/versions/{versionId}")
    public VersionResponse delete(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
            @PathVariable UUID versionId) {
        workspaceAccess.requireRead(workspaceId);
        return VersionResponse.from(documents.delete(workspaceId, documentId, versionId));
    }

    /**
     * 原文始终经由应用流式返回，浏览器不会得到 MinIO 端点、bucket 或签名 URL。
     * 下载文件名只来自已保存的安全文件名，并按照 UTF-8 写入 Content-Disposition。
     */
    @GetMapping("/documents/{documentId}/versions/{versionId}/source")
    public ResponseEntity<InputStreamResource> source(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
            @PathVariable UUID versionId) {
        workspaceAccess.requireRead(workspaceId);
        KnowledgeDocumentService.SourceView source = documents.openSource(workspaceId, documentId, versionId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(source.sourceName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(source.contentType()))
                .contentLength(source.contentLength())
                .body(new InputStreamResource(source.content()));
    }

    /** 将公开字符串收敛为唯一布尔语义，避免控制器把未知范围当成默认范围继续写入。 */
    private boolean organizationCommon(String scope) {
        if ("ORGANIZATION".equals(scope)) {
            return true;
        }
        if ("WORKSPACE".equals(scope)) {
            return false;
        }
        throw new IllegalArgumentException("不支持的知识范围 scope");
    }

    /** 对外版本投影只含公开 UUID 与展示字段，不泄露内部主键、对象键、摘要或 embedding。 */
    public record VersionResponse(
            UUID id,
            @JsonProperty("version_no") int versionNo,
            String status,
            @JsonProperty("source_name") String sourceName,
            @JsonProperty("created_at") OffsetDateTime createdAt) {

        /** 服务层实体到 API 契约的显式转换，防止 JPA 字段演进导致意外泄露。 */
        static VersionResponse from(KnowledgeDocumentVersion version) {
            return new VersionResponse(version.getPublicId(), version.getVersionNo(), version.getStatus().name(),
                    version.getSourceName(), version.getCreatedAt());
        }
    }

    /** 文档响应带受审核版本列表；范围仅以稳定枚举表达，不暴露关联 Workspace 内部 ID。 */
    public record DocumentResponse(UUID id, String title, String type, String scope, List<VersionResponse> versions) {

        /** 文档视图只允许当前 Workspace 的服务层结果转换为 API 响应。 */
        static DocumentResponse from(KnowledgeDocumentService.DocumentView view) {
            var document = view.document();
            return new DocumentResponse(document.getPublicId(), document.getTitle(), document.getDocumentType().name(),
                    document.isOrganizationCommon() ? "ORGANIZATION" : "WORKSPACE",
                    view.versions().stream().map(VersionResponse::from).toList());
        }
    }
}
