package com.insightflow.controller;

import com.insightflow.security.MemberManagementService;
import com.insightflow.security.MemberRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 成员创建的 HTTP 边界。
 *
 * <p>接口只接收当前 Workspace UUID，不允许客户端提交 organizationId 或内部主键；Owner 校验、密码哈希和成员关系写入全部收敛在命令服务中。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/members")
public class MemberController {

    /** 成员命令服务承载权限与事务边界，Controller 不直接操作仓储。 */
    private final MemberManagementService memberManagementService;

    /** 通过构造器注入以保证 API 边界可使用 MockMvc 独立验证。 */
    public MemberController(MemberManagementService memberManagementService) {
        this.memberManagementService = memberManagementService;
    }

    /**
     * 创建组织成员，并把其访问范围限制为路径指定的 Workspace。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse create(@PathVariable UUID workspaceId, @Valid @RequestBody CreateMemberRequest request) {
        return MemberResponse.from(memberManagementService.grantNewMember(
                workspaceId, request.username(), request.password(), request.role()));
    }

    /**
     * 密码只在请求中短暂存在，响应模型没有对应字段，避免意外日志或序列化泄露。
     */
    public record CreateMemberRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull MemberRole role) {
    }

    /**
     * 对外成员契约仅暴露账户 UUID、名称与当前组织角色。
     */
    public record MemberResponse(UUID userId, String username, MemberRole role) {
        /** 将命令结果投影成 HTTP 契约，隔离内部实体字段演进。 */
        static MemberResponse from(MemberManagementService.MemberResult result) {
            return new MemberResponse(result.userId(), result.username(), result.role());
        }
    }
}
