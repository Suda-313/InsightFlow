package com.insightflow.security;

import com.insightflow.entity.Organization;
import com.insightflow.repository.OrganizationRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地 bootstrap 与登录用例。
 *
 * <p>首个 Owner 只能在没有账号时通过部署环境设置的一次性口令创建；后续账号创建必须经成员管理流程，避免
 * 匿名注册绕过组织归属。</p>
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

    /** 账号持久化只由认证和成员用例访问。 */
    private final AppUserRepository userRepository;

    /** 首个账号需要绑定迁移创建的默认组织。 */
    private final OrganizationRepository organizationRepository;

    /** Owner 角色在 bootstrap 时写入组织成员关系。 */
    private final OrganizationMemberRepository organizationMemberRepository;

    /** BCrypt 比较和编码集中在 Spring Security 提供的实现中。 */
    private final PasswordEncoder passwordEncoder;

    /** HS256 令牌服务不持有成员角色。 */
    private final JwtTokenService jwtTokenService;

    /** 环境注入的一次性初始化口令，不得记录或返回。 */
    private final String bootstrapToken;

    /** 构造器显式声明认证边界依赖，便于使用 mock 验证无匿名注册。 */
    public AuthService(
            AppUserRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository organizationMemberRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            @Value("${insightflow.security.bootstrap-token:}") String bootstrapToken) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.organizationMemberRepository = organizationMemberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.bootstrapToken = bootstrapToken;
    }

    /**
     * 创建唯一的初始 Owner；调用成功后同一接口永久拒绝再次初始化。
     */
    @Transactional
    public LoginResult bootstrap(String username, String password, String suppliedBootstrapToken) {
        if (userRepository.count() != 0 || bootstrapToken.isBlank() || !bootstrapToken.equals(suppliedBootstrapToken)) {
            throw new AuthenticationRequiredException();
        }
        AppUser user = userRepository.save(AppUser.create(normalizeUsername(username), passwordEncoder.encode(requirePassword(password))));
        Organization organization = organizationRepository.findByDefaultOrganizationTrue()
                .orElseThrow(() -> new IllegalStateException("默认组织不存在，拒绝创建无归属 Owner"));
        organizationMemberRepository.save(OrganizationMember.grant(organization.getId(), user.getId(), MemberRole.OWNER));
        return new LoginResult(user.getPublicId(), user.getUsername(), jwtTokenService.issue(user.getPublicId()));
    }

    /**
     * 通过登录名与 BCrypt 哈希比较签发新 JWT；不存在、停用和密码错误统一返回未认证。
     */
    public LoginResult login(String username, String password) {
        AppUser user = userRepository.findByUsername(normalizeUsername(username))
                .filter(found -> !found.isDisabled())
                .filter(found -> passwordEncoder.matches(requirePassword(password), found.getPasswordHash()))
                .orElseThrow(AuthenticationRequiredException::new);
        return new LoginResult(user.getPublicId(), user.getUsername(), jwtTokenService.issue(user.getPublicId()));
    }

    /** 登录名统一转小写并限制长度，避免大小写差异产生多个账号。 */
    private String normalizeUsername(String username) {
        if (username == null || username.isBlank() || username.trim().length() > 100) {
            throw new AuthenticationRequiredException();
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** 首版密码仅设置最低长度，复杂度策略留给未来账户安全专项。 */
    private String requirePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new AuthenticationRequiredException();
        }
        return password;
    }

    /** 登录响应只返回公开账号标识、登录名和短期令牌。 */
    public record LoginResult(UUID userId, String username, String accessToken) {
    }
}
