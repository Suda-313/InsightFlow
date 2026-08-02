package com.insightflow.security;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 组织角色关系的持久化入口。
 *
 * <p>授权服务仅按用户、组织的内部关系键读取该表；调用方不能绕过 Workspace 所属组织直接查询角色。</p>
 */
public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, Long> {

    /** 返回用户在一个组织中的唯一实时角色。 */
    Optional<OrganizationMember> findByUserIdAndOrganizationId(Long userId, Long organizationId);

    /**
     * Workspace 列表按当前账户全部组织成员关系收敛，不能以全局 Workspace 列表替代。
     */
    List<OrganizationMember> findByUserId(Long userId);
}
