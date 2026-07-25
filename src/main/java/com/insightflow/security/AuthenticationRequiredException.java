package com.insightflow.security;

/**
 * 请求未携带可验证登录身份时的受控异常。
 *
 * <p>该异常不区分令牌缺失、格式错误或账号不存在，HTTP 层统一返回 401，避免泄露账号和令牌探测信息。</p>
 */
public class AuthenticationRequiredException extends RuntimeException {

    /** 固定用户可见消息不暴露认证内部细节。 */
    public AuthenticationRequiredException() {
        super("需要有效登录身份");
    }
}
