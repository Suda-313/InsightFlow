package com.insightflow.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一个已上传、等待映射或已完成导入的原始文件元数据。
 *
 * <p>实体不保存 CSV 正文；正文位于 MinIO 的 {@code objectKey}。{@code workspaceId} 和
 * {@code sourceId} 保证文件只能被所属 Workspace 的固定 CSV 来源处理。</p>
 */
@Entity
@Table(name = "import_file")
public class ImportFile {

    /**
     * 数据库内部主键，仅用于任务和反馈记录关联。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * API 路径使用的 UUIDv7，避免暴露递增文件编号。
     */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /**
     * 文件所属 Workspace，必须与关联 source 和异步任务完全一致。
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * 固定 CSV 来源的内部主键，支持外部引用在同一来源内稳定去重。
     */
    @Column(name = "source_id", nullable = false, updatable = false)
    private Long sourceId;

    /**
     * MinIO 对象键，格式由服务层生成并强制以 Workspace 公开 ID 为前缀。
     */
    @Column(name = "object_key", nullable = false, length = 300, updatable = false)
    private String objectKey;

    /**
     * 原始展示文件名，不用于对象路径构造，避免路径遍历和名称冲突。
     */
    @Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    private String originalFilename;

    /**
     * 经服务端白名单后的 MIME 类型；V1 只接受 text/csv 类型。
     */
    @Column(name = "content_type", nullable = false, length = 120, updatable = false)
    private String contentType;

    /**
     * 原始字节长度用于展示和审计，上传前已受 Spring multipart 限制保护。
     */
    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    /**
     * 整个原始文件的 SHA-256 摘要；可用于发现重复上传但不替代行级幂等。
     */
    @Column(name = "checksum_sha256", nullable = false, length = 64, updatable = false)
    private String checksumSha256;

    /**
     * 已确认的字段映射 JSON；未映射文件保持 null，不能启动导入。
     */
    @Column(name = "mapping_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String mappingJson;

    /**
     * uploaded / mapped / processing / processed / failed 等受控状态，是启动任务的服务端门禁。
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * pending / projecting / projected / rebuild_required / projection_failed 等受控状态。
     * 它独立于导入状态：CSV 已成功导入不等于它已经进入趋势、基线和预警看板。
     */
    @Column(name = "projection_status", nullable = false, length = 30)
    private String projectionStatus;

    /**
     * 原文件对象的保留期预留字段；V1 不在代码中写死生产清理时长。
     */
    @Column(name = "retention_until")
    private OffsetDateTime retentionUntil;

    /**
     * 元数据写入时刻；用于文件列表稳定排序。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 映射、处理成功或失败时刷新，便于客户端轮询文件状态。
     */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * JPA 所需构造器；所有不可变字段由 {@link #uploaded} 一次性赋值。
     */
    protected ImportFile() {
    }

    /**
     * 创建上传完成但尚未映射的文件记录；对象写成功后才持久化本实体。
     */
    public static ImportFile uploaded(
            Long workspaceId,
            Long sourceId,
            String objectKey,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String checksumSha256) {
        ImportFile file = new ImportFile();
        file.publicId = UuidCreator.getTimeOrdered();
        file.workspaceId = workspaceId;
        file.sourceId = sourceId;
        file.objectKey = objectKey;
        file.originalFilename = originalFilename;
        file.contentType = contentType;
        file.sizeBytes = sizeBytes;
        file.checksumSha256 = checksumSha256;
        file.status = "uploaded";
        file.projectionStatus = "pending";
        file.createdAt = OffsetDateTime.now();
        file.updatedAt = file.createdAt;
        return file;
    }

    /**
     * 保存已经按当前 CSV 表头验证通过的映射，之后才允许创建异步导入任务。
     */
    public void markMapped(String mappingJson) {
        this.mappingJson = mappingJson;
        this.status = "mapped";
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 在导入任务和文件同一事务内写入 processing，防止第二次启动或映射更新覆盖已冻结输入。
     */
    public void markProcessing() {
        this.status = "processing";
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 标记任务完成；成功行、重复行和失败摘要保存在关联任务中而非覆盖文件元数据。
     */
    public void markProcessed() {
        this.status = "processed";
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 标记不可重试的文件级失败；原对象仍保留以便受控排障或未来重试。
     */
    public void markFailed() {
        this.status = "failed";
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 自动投影命令已受理；该状态不会改变 CSV 的上传、映射或导入结果。
     */
    public void markProjectionPending() {
        this.projectionStatus = "pending";
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 投影 Worker 已取得文件的看板计算责任，报告任务不可使用此方法。
     */
    public void markProjecting() {
        this.projectionStatus = "projecting";
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 主题、日指标、基线和预警已在同一投影事务内落库，文件可被只读报告引用。
     */
    public void markProjected() {
        this.projectionStatus = "projected";
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 晚到历史反馈需要受控重建而不能直接插回已处理 EWMA，因此保留明确状态供看板展示。
     */
    public void markRebuildRequired() {
        this.projectionStatus = "rebuild_required";
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 投影执行失败时保留导入成功事实；后续租约重试可从该状态恢复而不重新上传 CSV。
     */
    public void markProjectionFailed() {
        this.projectionStatus = "projection_failed";
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 返回内部主键，仅供任务、来源和反馈事件关联使用。
     */
    public Long getId() {
        return id;
    }

    /**
     * 返回可公开使用的文件 UUIDv7。
     */
    public UUID getPublicId() {
        return publicId;
    }

    /**
     * 返回所属 Workspace 内部键，服务层用它执行强制隔离检查。
     */
    public Long getWorkspaceId() {
        return workspaceId;
    }

    /**
     * 返回关联来源内部键，导入 Worker 用它构造行级去重键。
     */
    public Long getSourceId() {
        return sourceId;
    }

    /**
     * 返回受控 MinIO 对象键；绝不把它直接暴露给客户端作为下载 URL。
     */
    public String getObjectKey() {
        return objectKey;
    }

    /**
     * 返回用户上传时的原始显示名称。
     */
    public String getOriginalFilename() {
        return originalFilename;
    }

    /**
     * 返回已白名单校验的内容类型。
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * 返回字节长度，用于文件列表和上传结果展示。
     */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /**
     * 返回只用于服务端比较的文件摘要，不在普通 API 中返回。
     */
    public String getChecksumSha256() {
        return checksumSha256;
    }

    /**
     * 返回映射 JSON 供异步 Worker 解析；未映射时为 null。
     */
    public String getMappingJson() {
        return mappingJson;
    }

    /**
     * 返回当前生命周期状态，客户端只能据此决定下一步可用操作。
     */
    public String getStatus() {
        return status;
    }

    /**
     * 返回看板投影状态，客户端据此区分“已导入”和“已出现在分析看板”。
     */
    public String getProjectionStatus() {
        return projectionStatus;
    }

    /**
     * 返回创建时刻，供 API 审计和稳定排序使用。
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 返回最后状态变更时刻，供轮询客户端判断是否刷新结果。
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
