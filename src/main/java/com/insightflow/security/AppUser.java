package com.insightflow.security;

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
 * 本地认证账号，不直接拥有组织或业务数据。
 *
 * <p>内部 {@code id} 仅用于成员关系表连接；对外和 JWT 只使用 {@code publicId}。密码字段只保存 BCrypt 哈希，
 * 禁止存储明文、可逆密文或任意登录 Token。</p>
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    /** 数据库内部关系主键，绝不进入 HTTP 响应、JWT 或审计目标字段。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 外部稳定账号标识，JWT 的 subject 只保存该值。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 登录名规范化后保持唯一；展示名等协作资料不在首版引入。 */
    @Column(nullable = false, unique = true, length = 100)
    private String username;

    /** BCrypt 单向哈希，不允许由 Controller 或响应 DTO 读取。 */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /** 账号创建时间只用于审计排序，不代表组织成员加入时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 非空表示账号停用；旧 JWT 仍需在每次请求时被拒绝。 */
    @Column(name = "disabled_at")
    private OffsetDateTime disabledAt;

    /** 仅供 JPA 反射，业务代码必须经命名工厂创建账号。 */
    protected AppUser() {
    }

    /**
     * 创建可登录的本地账号，调用方必须先完成用户名和密码策略校验。
     */
    public static AppUser create(String username, String passwordHash) {
        AppUser user = new AppUser();
        user.publicId = UuidCreator.getTimeOrdered();
        user.username = username;
        user.passwordHash = passwordHash;
        user.createdAt = OffsetDateTime.now();
        return user;
    }

    /** 内部成员关系使用的主键。 */
    public Long getId() { return id; }

    /** API 与 JWT 可安全暴露的账号 UUID。 */
    public UUID getPublicId() { return publicId; }

    /** 认证时用于查找账号的规范化登录名。 */
    public String getUsername() { return username; }

    /** 仅认证服务进行 BCrypt 比对时使用的单向哈希。 */
    public String getPasswordHash() { return passwordHash; }

    /** 禁用账号后即使 JWT 未过期也不得继续操作。 */
    public boolean isDisabled() { return disabledAt != null; }
}
