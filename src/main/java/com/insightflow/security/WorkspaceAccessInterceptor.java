package com.insightflow.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 对所有带 Workspace UUID 路径段的 HTTP API 统一执行读权限校验。
 *
 * <p>拦截器只负责 HTTP 路径到授权服务的桥接，不代替下游仓储的 workspace_id 过滤，也不承担写角色校验。这样能够避免多个 Controller 漏接基础访问控制，同时命令服务仍会执行更严格的角色检查。</p>
 */
@Component
public class WorkspaceAccessInterceptor implements HandlerInterceptor {

    /** 所有真实组织、角色与范围判断统一委托给服务层，拦截器不缓存权限。 */
    private final WorkspaceAccessService accessService;

    /** 构造器只注入统一授权入口，避免 Controller 之间存在不同的安全判断。 */
    public WorkspaceAccessInterceptor(WorkspaceAccessService accessService) {
        this.accessService = accessService;
    }

    /**
     * 仅拦截 /api/v1/workspaces/{uuid} 及其子路径；集合路径由专门的列表服务处理。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = "/api/v1/workspaces/";
        if (!path.startsWith(prefix)) {
            return true;
        }
        String remaining = path.substring(prefix.length());
        int nextSlash = remaining.indexOf('/');
        String candidate = nextSlash < 0 ? remaining : remaining.substring(0, nextSlash);
        if (candidate.isBlank()) {
            return true;
        }
        try {
            accessService.requireRead(UUID.fromString(candidate));
        } catch (IllegalArgumentException exception) {
            return true;
        }
        return true;
    }
}
