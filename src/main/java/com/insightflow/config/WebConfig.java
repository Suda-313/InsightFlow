package com.insightflow.config;

import com.insightflow.security.WorkspaceAccessInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    /** 所有 Workspace 明细路由共享同一权限拦截器，避免 Controller 漏接只读授权。 */
    private final WorkspaceAccessInterceptor workspaceAccessInterceptor;

    /** 显式注入保障 Web 层配置不绕过服务层授权规则。 */
    public WebConfig(WorkspaceAccessInterceptor workspaceAccessInterceptor) {
        this.workspaceAccessInterceptor = workspaceAccessInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
    }

    /**
     * 集合根路径不包含 Workspace UUID，由相应列表接口按成员范围过滤；其余路径统一进行基础读校验。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(workspaceAccessInterceptor).addPathPatterns("/api/v1/workspaces/**");
    }
}
