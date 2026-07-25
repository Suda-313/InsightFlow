package com.insightflow.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 本地账号的受限查询入口。
 *
 * <p>Controller 不得直接使用该仓储；认证服务使用登录名验证口令，JWT 过滤器使用 public ID 查询是否停用。</p>
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** 登录名为唯一凭据，查询结果仍需由认证服务比较 BCrypt 哈希。 */
    Optional<AppUser> findByUsername(String username);

    /**
     * 成员创建先检查规范化登录名，避免依赖唯一约束异常来表达可预期的业务冲突。
     */
    boolean existsByUsername(String username);

    /** JWT subject 只能解析为 public ID，禁止接收内部自增主键。 */
    Optional<AppUser> findByPublicId(UUID publicId);
}
