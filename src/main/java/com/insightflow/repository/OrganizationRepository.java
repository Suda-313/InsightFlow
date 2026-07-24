package com.insightflow.repository;

import com.insightflow.entity.Organization;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 组织归属读取端口。
 *
 * <p>P3 只允许 WorkspaceService 读取唯一默认组织；不能从 Controller 接收内部组织 ID，
 * 以免在尚无成员权限体系时伪造归属范围。</p>
 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /** 查找迁移保证唯一的默认组织；不存在意味着数据库初始化不完整，应阻止创建无归属 Workspace。 */
    Optional<Organization> findByDefaultOrganizationTrue();
}
