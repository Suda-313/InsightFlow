package com.insightflow.security;

/**
 * JWT 不可验证、被篡改或已过期时抛出的受控认证异常。
 *
 * <p>异常不携带令牌正文、签名、密钥或解析细节；HTTP 层据此仅返回统一的未认证响应，避免给攻击者提供
 * 令牌结构探测信息。</p>
 */
public class InvalidJwtTokenException extends RuntimeException {

    /**
     * 固定消息避免把密码学校验失败原因暴露给客户端。
     */
    public InvalidJwtTokenException() {
        super("登录令牌无效或已过期");
    }
}
