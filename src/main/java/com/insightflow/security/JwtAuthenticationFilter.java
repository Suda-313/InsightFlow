package com.insightflow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 将 Bearer JWT 转换为 Spring Security 中只含用户 public ID 的认证主体。
 *
 * <p>过滤器不读取组织、角色或 Workspace，不向请求写入任何可由客户端伪造的权限声明；后续服务层实时查询成员表。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 固定令牌格式和签名验证服务。 */
    private final JwtTokenService jwtTokenService;

    /** 过滤器链内的认证失败需要自行写入统一 JSON 错误契约。 */
    private final SecurityErrorResponseWriter errorResponseWriter;

    /** 构造器注入使 Web 安全配置可替换和独立测试。 */
    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, SecurityErrorResponseWriter errorResponseWriter) {
        this.jwtTokenService = jwtTokenService;
        this.errorResponseWriter = errorResponseWriter;
    }

    /**
     * 仅当 Authorization 使用 Bearer 方案时尝试认证；无 Token 的公开登录端点交给后续授权规则处理。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UUID userPublicId = jwtTokenService.verify(header.substring("Bearer ".length()).trim());
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userPublicId, null, List.of()));
            } catch (InvalidJwtTokenException exception) {
                errorResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "需要有效的登录凭证");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
