package com.insightflow.security;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Workspace 访问范围关系的持久化入口。
 *
 * <p>该表只决定成员是否可访问某个游戏/产品线；角色语义仍由 OrganizationMember 统一维护。</p>
 */
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    /** 用于确认非 Owner 具有目标工作区的显式访问关系。 */
    Optional<WorkspaceMember> findByUserIdAndWorkspaceId(Long userId, Long workspaceId);

    /**
     * 非 Owner 的可见 Workspace 范围只从此关系表读取，禁止以组织成员身份推导全量可见。
     */
    List<WorkspaceMember> findByUserId(Long userId);
}
