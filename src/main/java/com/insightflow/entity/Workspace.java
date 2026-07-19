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
 * Workspace 是所有业务数据的一级隔离边界。
 *
 * <p>数据库内部使用自增 {@code id} 保持关联和索引成本低；HTTP API、事件和 Trace 只使用
 * 时间有序的 UUIDv7 {@code publicId}，避免暴露内部行号，也便于跨系统引用。</p>
 */
@Entity
@Table(name = "workspace")
public class Workspace {

    /**
     * 仅供数据库外键关联的内部主键，绝不能序列化到对外 API。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 面向外部的稳定标识；由 UUIDv7 生成器创建，具备按生成时间大致有序的特性。
     */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /**
     * 用户可见的工作区名称；长度限制与 API 契约、数据库字段保持一致。
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 创建时刻使用带时区时间，不能由业务更新操作修改。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 供 JPA 反射创建实体使用；业务代码必须调用带名称的构造方法。
     */
    protected Workspace() {
    }

    /**
     * 创建工作区并一次性确定不可变的公开标识和创建时间。
     *
     * <p>UUIDv7 由成熟库生成，避免自行实现时间位、随机位和并发单调性逻辑。</p>
     */
    public Workspace(String name) {
        this.publicId = UuidCreator.getTimeOrdered();
        this.name = name;
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * 返回内部主键，仅供同进程领域与持久化层使用。
     */
    public Long getId() {
        return id;
    }

    /**
     * 返回可安全暴露到 API、SSE 与 Trace 的公开标识。
     */
    public UUID getPublicId() {
        return publicId;
    }

    /**
     * 返回用户可见名称；名称的校验由 API 层和领域创建流程共同保证。
     */
    public String getName() {
        return name;
    }

    /**
     * 返回不可变的创建时刻，用于审计和稳定排序，不能用内部自增 id 推断业务顺序。
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
