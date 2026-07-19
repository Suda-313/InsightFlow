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
 * Workspace 内一个可识别的反馈来源。
 *
 * <p>内部自增主键用于 {@link ImportFile} 和 {@link FeedbackEvent} 外键；对外 UUIDv7 预留给
 * 未来的数据源 API。{@code workspaceId} 是强制隔离字段，所有下游文件和事件必须继承它。</p>
 */
@Entity
@Table(name = "feedback_source")
public class FeedbackSource {

    /**
     * 数据库关联使用的内部主键，永不通过导入 API 返回。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 供未来数据源资源 API 使用的 UUIDv7，不能由客户端指定。
     */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /**
     * 一级隔离边界的内部键；服务层总是先验证对应 Workspace 存在。
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * 受控来源类型；CSV 入口固定为 file_import，避免伪装成真实外部 Connector。
     */
    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    /**
     * 工作区内可读名称；同一 Workspace 内唯一，方便后续数据源管理页面展示。
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * active / disabled 等受控状态，V1 上传流程只使用 active 来源。
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * 只承载非敏感来源配置；CSV 默认使用空 JSON 对象。
     */
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String configJson;

    /**
     * 创建时间供审计和稳定排序使用，不以内部 id 推断业务时间。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 状态或展示配置变化时更新；CSV 首版不提供公开编辑接口。
     */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * JPA 反射构造器；业务代码使用 {@link #fileImportSource(Long)} 创建。
     */
    protected FeedbackSource() {
    }

    /**
     * 为一个 Workspace 创建唯一的受控 CSV 来源，避免每个文件都创建无意义数据源。
     */
    public static FeedbackSource fileImportSource(Long workspaceId) {
        FeedbackSource source = new FeedbackSource();
        source.publicId = UuidCreator.getTimeOrdered();
        source.workspaceId = workspaceId;
        source.sourceType = "file_import";
        source.name = "CSV 文件导入";
        source.status = "active";
        source.configJson = "{}";
        source.createdAt = OffsetDateTime.now();
        source.updatedAt = source.createdAt;
        return source;
    }

    /**
     * 返回内部关联主键，只允许领域和持久化层使用。
     */
    public Long getId() {
        return id;
    }

    /**
     * 返回公开 UUID，供以后跨进程或 API 引用时使用。
     */
    public UUID getPublicId() {
        return publicId;
    }

    /**
     * 返回来源所属 Workspace 内部键，必须与其文件和反馈事件保持一致。
     */
    public Long getWorkspaceId() {
        return workspaceId;
    }
}
