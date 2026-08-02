package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 自动投影冻结的一份来源文件关联。
 *
 * <p>该实体只保存内部关联键，原始 CSV 仍只在 MinIO。workspaceId 重复保留是为了让每次关联查询
 * 都有显式隔离条件，而不依赖跨表推断。</p>
 */
@Entity
@Table(name = "projection_file")
public class ProjectionFile {

    /** 内部关联主键，不作为外部资源标识。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属自动投影内部键。 */
    @Column(name = "workspace_projection_id", nullable = false, updatable = false)
    private Long workspaceProjectionId;

    /** 强制租户隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 冻结的导入文件内部键。 */
    @Column(name = "import_file_id", nullable = false, updatable = false)
    private Long importFileId;

    /** 关联创建时刻，支持审计投影输入。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 所需构造器。 */
    protected ProjectionFile() {
    }

    /** 创建已校验 Workspace 归属的投影来源关联。 */
    public static ProjectionFile of(Long projectionId, Long workspaceId, Long importFileId) {
        ProjectionFile link = new ProjectionFile();
        link.workspaceProjectionId = projectionId;
        link.workspaceId = workspaceId;
        link.importFileId = importFileId;
        link.createdAt = OffsetDateTime.now();
        return link;
    }

    /** 返回冻结来源文件内部键，Worker 只用它与 Workspace 键共同推进状态。 */
    public Long getImportFileId() {
        return importFileId;
    }
}
