package com.insightflow.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 非 Owner 成员可访问某一游戏或产品线 Workspace 的范围关系。
 *
 * <p>内部 {@code id}、{@code workspaceId} 与 {@code userId} 仅用于授权查询。任何业务资源仍要按自己的
 * {@code workspace_id} 查询，成员关系不能替代下游数据隔离。</p>
 */
@Entity
@Table(name = "workspace_member")
public class WorkspaceMember {

    /** 内部关系主键，禁止暴露到 API。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被授权工作区的内部隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 获得访问范围的账号内部键。 */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 授权建立时间仅用于审计和稳定排序。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 仅供 JPA 反射。 */
    protected WorkspaceMember() {
    }

    /** 创建最小范围访问关系；角色仍由 OrganizationMember 统一决定。 */
    public static WorkspaceMember grant(Long workspaceId, Long userId) {
        WorkspaceMember member = new WorkspaceMember();
        member.workspaceId = workspaceId;
        member.userId = userId;
        member.createdAt = OffsetDateTime.now();
        return member;
    }

    /** 返回内部关系主键。 */
    public Long getId() { return id; }

    /** 返回被授权的 Workspace 内部键。 */
    public Long getWorkspaceId() { return workspaceId; }

    /** 返回获授权账号内部键。 */
    public Long getUserId() { return userId; }
}
