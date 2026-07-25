package com.insightflow.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 用户在组织中的角色关系。
 *
 * <p>内部 {@code id} 仅服务数据库关系；{@code organizationId} 与 {@code userId} 不出现在 API。角色是实时授权
 * 来源，任何 Workspace 写命令都必须同时验证该关系与 Workspace 范围。</p>
 */
@Entity
@Table(name = "organization_member")
public class OrganizationMember {

    /** 内部关系主键，不具有外部业务含义。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 成员所在组织的内部键，必须与目标 Workspace 的组织一致。 */
    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    /** 被授权账号的内部键，不能由请求方任意指定。 */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 固定枚举避免数据库中出现无法解释的自定义权限字符串。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    /** 成员关系建立时刻，用于成员授权审计。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 仅供 JPA 反射。 */
    protected OrganizationMember() {
    }

    /** 创建组织角色关系；重复关系由数据库唯一约束和服务层提前校验共同阻止。 */
    public static OrganizationMember grant(Long organizationId, Long userId, MemberRole role) {
        OrganizationMember member = new OrganizationMember();
        member.organizationId = organizationId;
        member.userId = userId;
        member.role = role;
        member.createdAt = OffsetDateTime.now();
        return member;
    }

    /** 返回内部主键供 JPA 关联使用。 */
    public Long getId() { return id; }

    /** 返回受授权组织的内部键。 */
    public Long getOrganizationId() { return organizationId; }

    /** 返回账号内部键以完成 Workspace 二次校验。 */
    public Long getUserId() { return userId; }

    /** 返回当前生效角色。 */
    public MemberRole getRole() { return role; }
}
