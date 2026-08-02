package com.insightflow.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 本地账号登录使用的最小 HS256 JWT 服务。
 *
 * <p>Payload 只保存用户 public ID 和过期 Unix 秒数。组织、Workspace 与角色始终从数据库读取，因此成员
 * 被撤销或角色被调整后，不会因为旧令牌中的过时声明而继续取得权限。</p>
 */
public class JwtTokenService {

    /** 标准 JWT 头固定为 HS256，服务不接受调用方指定算法以避免算法降级。 */
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    /** 令牌至少使用 256 bit 等价长度的部署密钥，开发或生产环境均不允许空密钥。 */
    private final byte[] secret;

    /** 令牌有效期以分钟配置，既控制会话风险也避免前端频繁刷新登录。 */
    private final long expirationSeconds;

    /** 注入时钟确保过期边界可测试，并避免业务代码直接依赖系统时间。 */
    private final Clock clock;

    /**
     * 创建令牌服务。
     *
     * @param secretValue 部署环境注入的 HS256 密钥，不写入日志或响应
     * @param expirationMinutes 正整数有效分钟数
     * @param clock 当前时间来源
     */
    public JwtTokenService(String secretValue, long expirationMinutes, Clock clock) {
        if (secretValue == null || secretValue.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT 密钥长度至少为 32 字节");
        }
        if (expirationMinutes <= 0) {
            throw new IllegalArgumentException("JWT 有效期必须为正数");
        }
        this.secret = secretValue.getBytes(StandardCharsets.UTF_8);
        this.expirationSeconds = expirationMinutes * 60;
        this.clock = clock;
    }

    /**
     * 为已认证用户签发令牌；用户 UUID 是唯一声明，权限由后续请求实时查询数据库决定。
     */
    public String issue(UUID userPublicId) {
        long expiresAt = Instant.now(clock).getEpochSecond() + expirationSeconds;
        String payload = "{\"sub\":\"" + userPublicId + "\",\"exp\":" + expiresAt + "}";
        String unsigned = encode(HEADER) + "." + encode(payload);
        return unsigned + "." + encodeBytes(sign(unsigned));
    }

    /**
     * 验证签名、结构和过期时间，并返回经过认证的用户公开标识。
     *
     * <p>所有失败路径统一抛出同一种异常，不能让调用方根据错误类型推测密钥、Token 格式或账号存在性。</p>
     */
    public UUID verify(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(decode(parts[0]))) {
                throw new InvalidJwtTokenException();
            }
            String unsigned = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(sign(unsigned), decodeBytes(parts[2]))) {
                throw new InvalidJwtTokenException();
            }
            String payload = decode(parts[1]);
            UUID userId = UUID.fromString(extractString(payload, "sub"));
            long expiration = extractLong(payload, "exp");
            if (Instant.now(clock).getEpochSecond() >= expiration) {
                throw new InvalidJwtTokenException();
            }
            return userId;
        } catch (InvalidJwtTokenException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidJwtTokenException();
        }
    }

    /** 以 URL-safe Base64 生成不带填充的 JWT 段。 */
    private String encode(String value) {
        return encodeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析标准 UTF-8 JSON 段；错误将由上层收敛为认证失败。 */
    private String decode(String value) {
        return new String(decodeBytes(value), StandardCharsets.UTF_8);
    }

    /** 使用固定算法和部署密钥计算签名，调用方无法选择算法或 Key ID。 */
    private byte[] sign(String unsigned) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("JWT 签名组件不可用", exception);
        }
    }

    /** JWT 规定使用 URL-safe Base64 且省略填充字符。 */
    private String encodeBytes(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** 所有 Base64 格式错误都由 verify 统一转为无效令牌。 */
    private byte[] decodeBytes(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    /** 仅提取双引号中的字符串声明，返回值不包含 JSON 语法字符。 */
    private String extractString(String payload, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\\"" + field + "\\\":\\\"(?<value>[^\\\"]+)\\\"")
                .matcher(payload);
        if (!matcher.find()) {
            throw new InvalidJwtTokenException();
        }
        return matcher.group("value");
    }

    /** 数字声明不接受小数、负数或调用方附加的任意 JSON 结构。 */
    private long extractLong(String payload, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\\"" + field + "\\\":(?<value>\\d+)")
                .matcher(payload);
        if (!matcher.find()) {
            throw new InvalidJwtTokenException();
        }
        return Long.parseLong(matcher.group("value"));
    }
}
