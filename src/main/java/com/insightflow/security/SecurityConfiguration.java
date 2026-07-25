package com.insightflow.security;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * P4 本地 JWT 安全边界。
 *
 * <p>会话保持无状态，登录与健康检查为唯一公开 API；其他请求必须先经 JWT 认证，业务级 Workspace 和角色检查
 * 仍在服务层执行。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    /** 统一提供 BCrypt，避免认证服务自行选择弱哈希算法。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** JWT 密钥和有效期只从环境覆盖的配置读取，绝不提供可用默认密钥。 */
    @Bean
    public JwtTokenService jwtTokenService(
            @Value("${insightflow.security.jwt-secret:}") String secret,
            @Value("${insightflow.security.jwt-expiration-minutes:60}") long expirationMinutes) {
        return new JwtTokenService(secret, expirationMinutes, Clock.systemUTC());
    }

    /** 配置 JSON API 的无状态认证，拒绝时只返回状态码而不重定向到表单页面。 */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtFilter, SecurityErrorResponseWriter errorResponseWriter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/**", "/actuator/health", "/", "/index.html", "/assets/**", "/vite.svg").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errorResponseWriter.write(
                                response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "需要有效的登录凭证"))
                        .accessDeniedHandler((request, response, exception) -> errorResponseWriter.write(
                                response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "无权访问当前资源")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
