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

/**
 * Workspace 私有稳定主题目录；同一 (workspace_id, canonical_key) 唯一。
 *
 * <p>内部 id 供 feedback_issue_link / cell_issue 关联；public_id 留给未来看板路径。
 * 规则命中后由 IssueCatalogService find-or-create，不允许多次创建同 key。</p>
 */
@Entity
@Table(name = "issue_catalog")
public class IssueCatalog {

    /**
     * 内部主键，仅供关联表使用。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 看板路径用的 UUIDv7。
     */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /**
     * 一级租户隔离键。
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * 稳定主题键，与规则 canonical_key 一致。
     */
    @Column(name = "canonical_key", nullable = false, length = 120, updatable = false)
    private String canonicalKey;

    /**
     * 用户可读主题名。
     */
    @Column(name = "canonical_name", nullable = false, length = 200)
    private String canonicalName;

    /**
     * active / excluded / expired。
     */
    @Column(nullable = false, length = 30)
    private String status;

    /**
     * 主题首次出现的业务时间。
     */
    @Column(name = "first_seen_at")
    private OffsetDateTime firstSeenAt;

    /**
     * 主题最近一次被命中的业务时间。
     */
    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    /**
     * 记录首次写入时刻。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 记录最近一次更新时间；初次写入时与 createdAt 相同。
     */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * JPA 反射构造器；业务代码使用 {@link #create} 工厂方法。
     */
    protected IssueCatalog() {
    }

    /**
     * 创建首次出现的主题；status 固定 active，首末出现时间相同。
     *
     * @param workspaceId   一级租户隔离键
     * @param canonicalKey  稳定主题键
     * @param canonicalName 用户可读主题名
     * @return 新建的活跃主题目录
     */
    public static IssueCatalog create(Long workspaceId, String canonicalKey, String canonicalName) {
        IssueCatalog catalog = new IssueCatalog();
        OffsetDateTime now = OffsetDateTime.now();
        catalog.publicId = UuidCreator.getTimeOrdered();
        catalog.workspaceId = workspaceId;
        catalog.canonicalKey = canonicalKey;
        catalog.canonicalName = canonicalName;
        catalog.status = "active";
        catalog.firstSeenAt = now;
        catalog.lastSeenAt = now;
        catalog.createdAt = now;
        catalog.updatedAt = now;
        return catalog;
    }

    /**
     * 命中既有主题时刷新末次出现时间。
     */
    public void touchLastSeen() {
        this.lastSeenAt = OffsetDateTime.now();
        this.updatedAt = this.lastSeenAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public String getCanonicalKey() {
        return canonicalKey;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getFirstSeenAt() {
        return firstSeenAt;
    }

    public OffsetDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
