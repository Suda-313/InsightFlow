package com.insightflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 本地登录令牌的最小契约。
 *
 * <p>令牌只携带用户公开标识和过期时间；组织、Workspace 与角色不写入 Token，避免成员权限变更后旧 Token
 * 继续越权。</p>
 */
class JwtTokenServiceTest {

    /** 固定时钟使过期边界可重复验证，而不依赖机器当前时间。 */
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC);

    /**
     * 有效签名令牌必须还原原始用户公开 ID；调用方据此再从数据库读取当前成员关系。
     */
    @Test
    void issuesAndVerifiesPublicUserIdentity() {
        JwtTokenService service = new JwtTokenService("test-secret-key-with-32-bytes-minimum", 60, clock);
        UUID userId = UUID.randomUUID();

        String token = service.issue(userId);

        assertThat(service.verify(token)).isEqualTo(userId);
    }

    /**
     * 已过期令牌不能继续作为请求身份，避免角色撤销后之外的会话无限存活。
     */
    @Test
    void rejectsExpiredToken() {
        JwtTokenService issuer = new JwtTokenService("test-secret-key-with-32-bytes-minimum", 1, clock);
        String token = issuer.issue(UUID.randomUUID());
        JwtTokenService verifier = new JwtTokenService("test-secret-key-with-32-bytes-minimum", 1,
                Clock.fixed(Instant.parse("2026-07-25T00:01:01Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidJwtTokenException.class);
    }
}
