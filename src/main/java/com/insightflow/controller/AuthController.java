package com.insightflow.controller;

import com.insightflow.security.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地账号 bootstrap 与登录 HTTP 边界。
 *
 * <p>Controller 只接收用户名、口令和 bootstrap 口令；不读取仓储、不处理 BCrypt，也不暴露内部账号或成员主键。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** 认证用例负责所有账号、组织归属和令牌签发规则。 */
    private final AuthService authService;

    /** 显式注入使 HTTP 契约可独立 mock 测试。 */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 仅在系统尚无账号时创建首个 Owner。 */
    @PostMapping("/bootstrap")
    public LoginResponse bootstrap(@Valid @RequestBody BootstrapRequest request) {
        return LoginResponse.from(authService.bootstrap(request.username(), request.password(), request.bootstrapToken()));
    }

    /** 登录成功后返回短期 Bearer Token，前端不保存密码或角色。 */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return LoginResponse.from(authService.login(request.username(), request.password()));
    }

    /** 首次初始化额外验证一次部署环境口令，不能使用默认值。 */
    public record BootstrapRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 200) String bootstrapToken) {
    }

    /** 正常登录不接受或返回任何组织、角色和内部主键字段。 */
    public record LoginRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    /** API 响应仅公开账号 UUID、规范化登录名和 access token。 */
    public record LoginResponse(UUID userId, String username, String accessToken) {
        /** 将领域结果显式投影为 HTTP 契约，防止后续账号字段泄露。 */
        static LoginResponse from(AuthService.LoginResult result) {
            return new LoginResponse(result.userId(), result.username(), result.accessToken());
        }
    }
}
