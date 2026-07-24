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
 * 企业知识与 Workspace 的共同归属边界。
 *
 * <p>P3 只使用组织处理文档可见范围，不引入用户、成员或角色。内部 {@code id} 仅供关系表连接，
 * 对外组织管理 API 未来只能使用 {@code publicId}，避免暴露连续数据库行号。</p>
 */
@Entity
@Table(name = "organization")
public class Organization {

    /** 内部关系主键，不能出现在 HTTP 响应、Agent 证据或日志字段中。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 未来组织管理 API 的稳定标识；P3 即使只有默认组织也保持与 Workspace 相同的外部标识约束。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 当前企业可读名称，不承担认证身份或角色语义。 */
    @Column(nullable = false, length = 100)
    private String name;

    /** P3 用唯一默认组织承接无登录状态下的新建 Workspace；P4 组织管理启用后不再由调用方传入该字段。 */
    @Column(name = "is_default", nullable = false, updatable = false)
    private boolean defaultOrganization;

    /** 组织建立时刻只用于审计与稳定排序，不能因文档或 Workspace 写入被改写。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 仅供 JPA 反射创建；业务代码必须经由命名工厂创建组织。 */
    protected Organization() {
    }

    /**
     * 创建系统默认组织。
     *
     * <p>迁移中的默认组织已有固定 public_id；该工厂用于单元测试和未来受控的初始化流程，
     * 不能替代 P4 的组织管理用例。</p>
     *
     * @param name 默认组织展示名称
     * @return 具备外部标识和不可变创建时间的默认组织
     */
    public static Organization defaultOrganization(String name) {
        Organization organization = new Organization();
        organization.publicId = UuidCreator.getTimeOrdered();
        organization.name = name;
        organization.defaultOrganization = true;
        organization.createdAt = OffsetDateTime.now();
        return organization;
    }

    /** 返回内部关系主键，仅供同一进程的用例和持久化层使用。 */
    public Long getId() {
        return id;
    }

    /** 返回未来外部 API 可安全公开的组织标识。 */
    public UUID getPublicId() {
        return publicId;
    }

    /** 返回组织展示名称。 */
    public String getName() {
        return name;
    }

    /** 返回是否为 P3 自动归属使用的唯一默认组织。 */
    public boolean isDefaultOrganization() {
        return defaultOrganization;
    }

    /** 返回不可变的组织创建时刻。 */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
