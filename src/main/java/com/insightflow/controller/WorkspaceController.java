package com.insightflow.controller;

import com.insightflow.entity.Workspace;
import com.insightflow.service.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Workspace 的 HTTP 边界。
 *
 * <p>Controller 只接受和返回 {@code publicId}；内部自增主键不属于公开契约。后续所有
 * 工作区内资源都应复用路径中的 {@code workspaceId} 做访问范围校验。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    /**
     * 用例服务负责业务创建和读取，Controller 不直接访问 JPA 仓储。
     */
    private final WorkspaceService workspaceService;

    /**
     * 使用构造器注入，保持 API 层依赖显式且容易测试。
     */
    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 创建工作区并返回 201 与 Location 头。
     *
     * <p>请求参数先在边界校验，成功后只返回公开标识，符合 V1 的 API 契约。</p>
     */
    @PostMapping
    public ResponseEntity<WorkspaceResponse> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        Workspace workspace = workspaceService.create(request.name());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{workspaceId}")
                .buildAndExpand(workspace.getPublicId())
                .toUri();
        return ResponseEntity.created(location).body(WorkspaceResponse.from(workspace));
    }

    /**
     * 读取单个工作区；服务层将不存在的公开标识转换为受控的 404 响应。
     */
    @GetMapping("/{workspaceId}")
    public WorkspaceResponse get(@PathVariable UUID workspaceId) {
        return WorkspaceResponse.from(workspaceService.get(workspaceId));
    }

    /** 列出所有工作区，按创建时间倒序。 */
    @GetMapping
    public List<WorkspaceResponse> list() {
        return workspaceService.listAll().stream().map(WorkspaceResponse::from).toList();
    }

    /**
     * 创建请求的最小契约；名称不允许为空或超过数据库可存储上限。
     */
    public record CreateWorkspaceRequest(
            @NotBlank @Size(max = 100) String name) {
    }

    /**
     * 对外响应模型，不包含内部主键或未来的成员、策略等关联数据。
     */
    public record WorkspaceResponse(UUID publicId, String name, OffsetDateTime createdAt) {
        /**
         * 将领域实体显式投影为 API 响应，防止 JPA 实体随字段演进意外泄漏。
         */
        static WorkspaceResponse from(Workspace workspace) {
            return new WorkspaceResponse(
                    workspace.getPublicId(), workspace.getName(), workspace.getCreatedAt());
        }
    }
}
