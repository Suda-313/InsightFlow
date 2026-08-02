package com.insightflow.security;

import com.insightflow.entity.Workspace;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner 为当前组织新增成员并授予单个 Workspace 可见范围的命令服务。
 *
 * <p>首版不提供匿名注册或跨组织账户复用入口：新账户与组织角色、Workspace 范围在同一事务内创建，防止只建账号未授权或只授权无账户的半完成状态。
 * 角色属于组织，WorkspaceMember 只表达范围；两者分别由 {@link OrganizationMember} 与 {@link WorkspaceMember} 保存。</p>
 */
@Service
@Transactional
public class MemberManagementService {

    /** 所有成员命令先经此服务核验 Owner 与 Workspace 范围。 */
    private final WorkspaceAccessService accessService;

    /** 账户仓储只在认证和成员管理边界使用，Controller 不直接访问。 */
    private final AppUserRepository userRepository;

    /** 写入组织角色这一唯一角色事实来源。 */
    private final OrganizationMemberRepository organizationMemberRepository;

    /** 写入非 Owner 的精确 Workspace 可见范围；Owner 也记录当前范围以便审计。 */
    private final WorkspaceMemberRepository workspaceMemberRepository;

    /** 密码进入数据库前必须完成 BCrypt 单向哈希。 */
    private final PasswordEncoder passwordEncoder;

    /** 构造器显式声明成员创建的权限、身份和持久化依赖。 */
    public MemberManagementService(
            WorkspaceAccessService accessService,
            AppUserRepository userRepository,
            OrganizationMemberRepository organizationMemberRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            PasswordEncoder passwordEncoder) {
        this.accessService = accessService;
        this.userRepository = userRepository;
        this.organizationMemberRepository = organizationMemberRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 创建新本地账户，并在同一事务中授予组织角色和当前 Workspace 范围。
     *
     * <p>只允许 Owner 调用；密码规则与登录一致，避免成员创建绕过认证边界。组织范围始终从已授权 Workspace 反查，客户端无法伪造 organizationId。</p>
     */
    public MemberResult grantNewMember(UUID workspacePublicId, String username, String password, MemberRole role) {
        Workspace workspace = accessService.requireRole(workspacePublicId, MemberRole.OWNER);
        String normalizedUsername = normalizeUsername(username);
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new MembershipConflictException("登录名已存在，请使用其他登录名");
        }
        AppUser user = userRepository.save(AppUser.create(normalizedUsername, passwordEncoder.encode(requirePassword(password))));
        organizationMemberRepository.save(OrganizationMember.grant(workspace.getOrganizationId(), user.getId(), requireRole(role)));
        workspaceMemberRepository.save(WorkspaceMember.grant(workspace.getId(), user.getId()));
        return new MemberResult(user.getPublicId(), user.getUsername(), role);
    }

    /**
     * 账号名规范化与认证流程保持一致，避免大小写差异创建两个逻辑相同的账户。
     */
    private String normalizeUsername(String username) {
        if (username == null || username.isBlank() || username.trim().length() > 100) {
            throw new IllegalArgumentException("登录名不能为空且不能超过 100 个字符");
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * BCrypt 对 72 字节输入有边界，首版明确限制字符长度，避免不同实现截断后造成登录歧义。
     */
    private String requirePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new IllegalArgumentException("密码长度必须在 8 到 72 个字符之间");
        }
        return password;
    }

    /**
     * 防止空角色进入持久层；允许的角色枚举由数据库约束与领域枚举共同固定。
     */
    private MemberRole requireRole(MemberRole role) {
        if (role == null) {
            throw new IllegalArgumentException("成员角色不能为空");
        }
        return role;
    }

    /**
     * 成员创建完成后仅返回外部 UUID、规范化用户名和角色，不返回内部主键或密码相关数据。
     */
    public record MemberResult(UUID userId, String username, MemberRole role) {
    }
}
