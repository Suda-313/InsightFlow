package com.insightflow.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 从 Spring Security 上下文读取已认证账号的唯一入口。
 *
 * <p>JWT 过滤器只把用户 public ID 放入 principal。本组件不信任任何 Controller 参数，也不在此缓存角色，
 * 后续授权服务会重新读取数据库成员关系。</p>
 */
@Component
public class CurrentUser {

    /**
     * 返回当前请求已认证账号的公开 UUID。
     *
     * <p>匿名、错误类型 principal 或未完成认证都统一视为未登录，不能让调用方用用户名或内部主键替代身份。</p>
     */
    public UUID requirePublicId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UUID publicId)) {
            throw new AuthenticationRequiredException();
        }
        return publicId;
    }
}
