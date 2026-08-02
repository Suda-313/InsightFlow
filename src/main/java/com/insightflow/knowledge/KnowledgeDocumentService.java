package com.insightflow.knowledge;

import com.insightflow.entity.KnowledgeDocument;
import com.insightflow.entity.KnowledgeDocumentType;
import com.insightflow.entity.KnowledgeDocumentVersion;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.KnowledgeDocumentRepository;
import com.insightflow.repository.KnowledgeDocumentVersionRepository;
import com.insightflow.repository.OrganizationRepository;
import com.insightflow.service.WorkspaceService;
import com.insightflow.storage.KnowledgeObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识上传用例。
 *
 * <p>上传只建立待审核版本；发布、切片与嵌入会在后续受控步骤执行，因此待审核内容不会进入 RAG。</p>
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeDocumentService {
    private final WorkspaceService workspaceService;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeDocumentVersionRepository versionRepository;
    private final KnowledgeObjectStorage objectStorage;
    /** 对象键按组织公开 UUID 分区，避免组织通用文档被上传 Workspace 的目录语义绑定。 */
    private final OrganizationRepository organizationRepository;

    /** 所有副作用均显式注入，便于验证上传不会偷调用模型或检索仓储。 */
    public KnowledgeDocumentService(WorkspaceService workspaceService, KnowledgeDocumentRepository documentRepository,
            KnowledgeDocumentVersionRepository versionRepository, KnowledgeObjectStorage objectStorage,
            OrganizationRepository organizationRepository) {
        this.workspaceService = workspaceService; this.documentRepository = documentRepository;
        this.versionRepository = versionRepository; this.objectStorage = objectStorage;
        this.organizationRepository = organizationRepository;
    }

    /** 当前 Workspace 只可创建组织通用或自身专属文档，不能上传到其他 Workspace。 */
    @Transactional
    public KnowledgeDocumentVersion upload(UUID workspacePublicId, UploadCommand command) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        MultipartFile file = requireTextFile(command.file());
        KnowledgeDocument document = command.organizationCommon()
                ? KnowledgeDocument.organizationCommon(workspace.getOrganizationId(), command.type(), command.title())
                : KnowledgeDocument.workspaceScoped(workspace.getOrganizationId(), workspace.getId(), command.type(), command.title());
        KnowledgeDocument saved = documentRepository.save(document);
        return savePendingVersion(workspace, saved, file, command.metadata());
    }

    /**
     * 向已有文档追加上传待审核版本；标题与类型沿用文档本体，避免同文档多版本在列表里语义分裂。
     */
    @Transactional
    public KnowledgeDocumentVersion uploadVersion(UUID workspacePublicId, UUID documentPublicId, MultipartFile file,
            KnowledgeDocumentVersion.VersionMetadata metadata) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        KnowledgeDocument document = requireDocumentInWorkspace(workspace, documentPublicId);
        return savePendingVersion(workspace, document, requireTextFile(file), metadata);
    }

    /** 列出当前 Workspace 可见的组织通用和自身专属文档及其版本，绝不返回其他游戏专属条目。 */
    public List<DocumentView> list(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        List<KnowledgeDocument> documents = documentRepository.findByOrganizationIdOrderByCreatedAtDesc(workspace.getOrganizationId()).stream()
                .filter(document -> document.getTargetWorkspaceId() == null || document.getTargetWorkspaceId().equals(workspace.getId()))
                .toList();
        if (documents.isEmpty()) {
            return List.of();
        }
        List<Long> documentIds = documents.stream().map(KnowledgeDocument::getId).toList();
        Map<Long, List<KnowledgeDocumentVersion>> versionsByDocumentId = versionRepository
                .findByDocumentIdInOrderByDocumentIdAscVersionNoDesc(documentIds).stream()
                .collect(Collectors.groupingBy(KnowledgeDocumentVersion::getDocumentId));
        return documents.stream()
                .map(document -> new DocumentView(document, versionsByDocumentId.getOrDefault(document.getId(), List.of())))
                .toList();
    }

    /** 失效当前已发布版本；范围校验复用文档归属，避免仅凭版本 UUID 越权。 */
    @Transactional
    public KnowledgeDocumentVersion expire(UUID workspacePublicId, UUID documentPublicId, UUID versionPublicId) {
        KnowledgeDocumentVersion version = requireVersion(workspacePublicId, documentPublicId, versionPublicId);
        version.expire(java.time.OffsetDateTime.now());
        return versionRepository.save(version);
    }

    /** 逻辑删除待审核或历史失效版本；已发布版本必须先失效以保留审计语义。 */
    @Transactional
    public KnowledgeDocumentVersion delete(UUID workspacePublicId, UUID documentPublicId, UUID versionPublicId) {
        KnowledgeDocumentVersion version = requireVersion(workspacePublicId, documentPublicId, versionPublicId);
        version.delete(java.time.OffsetDateTime.now());
        return versionRepository.save(version);
    }

    /** 打开受当前 Workspace 授权的原文；返回流由 HTTP 响应完成后关闭。 */
    public SourceView openSource(UUID workspacePublicId, UUID documentPublicId, UUID versionPublicId) {
        KnowledgeDocumentVersion version = requireVersion(workspacePublicId, documentPublicId, versionPublicId);
        return new SourceView(version.getSourceName(), version.getContentType(), version.getContentLength(), objectStorage.open(version.getObjectKey()));
    }

    /** 统一解析文档、组织和 Workspace 范围，再读取同一文档内的版本。 */
    private KnowledgeDocumentVersion requireVersion(UUID workspacePublicId, UUID documentPublicId, UUID versionPublicId) {
        KnowledgeDocument document = requireDocumentInWorkspace(workspaceService.get(workspacePublicId), documentPublicId);
        return versionRepository.findByPublicIdAndDocumentId(versionPublicId, document.getId()).orElseThrow();
    }

    /** 文档归属校验复用于追加上传、失效与原文读取，防止仅凭 publicId 跨 Workspace 写入。 */
    private KnowledgeDocument requireDocumentInWorkspace(Workspace workspace, UUID documentPublicId) {
        KnowledgeDocument document = documentRepository.findByPublicId(documentPublicId).orElseThrow();
        if (!document.getOrganizationId().equals(workspace.getOrganizationId())
                || (document.getTargetWorkspaceId() != null && !document.getTargetWorkspaceId().equals(workspace.getId()))) {
            throw new IllegalArgumentException("知识文档不属于当前工作区可见范围");
        }
        return document;
    }

    /** 待审核版本在对象存储写入成功后才落库，版本号在同一文档内单调递增。 */
    private KnowledgeDocumentVersion savePendingVersion(Workspace workspace, KnowledgeDocument document, MultipartFile file,
            KnowledgeDocumentVersion.VersionMetadata metadata) {
        int versionNo = versionRepository.findTopByDocumentIdOrderByVersionNoDesc(document.getId()).map(v -> v.getVersionNo() + 1).orElse(1);
        String objectKey = "knowledge/" + organizationPublicId(workspace.getOrganizationId()) + "/"
                + document.getPublicId() + "/v" + versionNo + "/source";
        String contentType = contentType(file.getOriginalFilename());
        put(file, objectKey, contentType);
        return versionRepository.save(KnowledgeDocumentVersion.pending(document.getId(), versionNo, objectKey,
                checksum(file), safeName(file.getOriginalFilename()), contentType, file.getSize(), normalizeMetadata(metadata)));
    }

    /** 空串归一化为 null，避免数据库与检索侧对“空元数据”语义不一致。 */
    private KnowledgeDocumentVersion.VersionMetadata normalizeMetadata(KnowledgeDocumentVersion.VersionMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return new KnowledgeDocumentVersion.VersionMetadata(
                blankToNull(metadata.sourceUrl()),
                metadata.sourceCollectedAt(),
                metadata.effectiveFrom(),
                metadata.effectiveTo(),
                blankToNull(metadata.owner()),
                blankToNull(metadata.factBoundary()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 上传路径只按组织公开 UUID 分区，既不暴露内部组织键，也不把组织通用文档错误归属到某个游戏 Workspace。
     */
    private UUID organizationPublicId(Long organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalStateException("知识文档所属组织不存在"))
                .getPublicId();
    }

    /** 仅接受首版明确支持的 UTF-8 文本扩展名；实际编码在发布读取时再次严格解码。 */
    private MultipartFile requireTextFile(MultipartFile file) {
        String name = file == null ? "" : safeName(file.getOriginalFilename()).toLowerCase();
        if (file == null || file.isEmpty() || (!name.endsWith(".md") && !name.endsWith(".markdown") && !name.endsWith(".txt"))) throw new IllegalArgumentException("仅支持非空 Markdown 或 TXT 文件");
        return file;
    }

    /** 原文先成功写入受控对象存储，之后才持久化待审核版本。 */
    private void put(MultipartFile file, String key, String type) { try (InputStream in = file.getInputStream()) { objectStorage.put(key, in, file.getSize(), type); } catch (IOException e) { throw new IllegalArgumentException("无法读取知识文件", e); } }
    /** 计算 SHA-256 只保存摘要，不把原文复制到关系库。 */
    private String checksum(MultipartFile file) { try (InputStream in = file.getInputStream()) { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())); } catch (Exception e) { throw new IllegalArgumentException("无法校验知识文件", e); } }
    /** 由扩展名生成受控 MIME，不信任浏览器上传的 content-type。 */
    private String contentType(String name) { return safeName(name).toLowerCase().endsWith(".txt") ? "text/plain" : "text/markdown"; }
    /** 原文件名只用于显示，不能携带路径参与对象键。 */
    private String safeName(String name) { if (name == null || name.isBlank()) return "knowledge.txt"; String n = name.replace('\\', '/'); return n.substring(n.lastIndexOf('/') + 1); }

    /** 上传命令不包含目标 Workspace ID，专属范围永远固定为路径中的当前 Workspace。 */
    public record UploadCommand(
            String title,
            KnowledgeDocumentType type,
            boolean organizationCommon,
            MultipartFile file,
            KnowledgeDocumentVersion.VersionMetadata metadata) {
    }

    /** 文档管理列表视图仅暴露 publicId 和状态，不泄露内部组织、Workspace 或对象键。 */
    public record DocumentView(KnowledgeDocument document, List<KnowledgeDocumentVersion> versions) { }

    /** 原文下载的服务边界，不向 Controller 暴露对象键或存储凭据。 */
    public record SourceView(String sourceName, String contentType, long contentLength, InputStream content) { }
}
