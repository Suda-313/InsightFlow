package com.insightflow.security;

import com.insightflow.entity.Workspace;
import com.insightflow.repository.WorkspaceRepository;
import com.insightflow.repository.OrganizationRepository;
import com.insightflow.service.WorkspaceService;
import java.util.Comparator;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P4 所有 Workspace 读写路径共享的身份、组织和范围校验服务。
 *
 * <p>校验顺序固定为：解析 Workspace -> 解析当前账号 -> 校验组织成员 -> 校验 Owner 或 Workspace 成员 -> 校验
 * 命令角色。下游 Repository 仍必须按 workspace_id 查询，本服务不能替代数据隔离。</p>
 */
@Service
@Transactional(readOnly = true)
public class WorkspaceAccessService {

    /** 将外部 Workspace UUID 解析为可信内部隔离键。 */
    private final WorkspaceService workspaceService;

    /** 当前请求的账号 public ID 从安全上下文获取。 */
    private final CurrentUser currentUser;

    /** 用 public ID 获取账号内部键并检查停用状态。 */
    private final AppUserRepository userRepository;

    /** 组织角色关系是实时授权事实来源。 */
    private final OrganizationMemberRepository organizationMemberRepository;

    /** 非 Owner 成员只能访问显式加入的 Workspace。 */
    private final WorkspaceMemberRepository workspaceMemberRepository;

    /** Workspace 列表查询仅用于将已授权成员关系投影成可见范围，不负责业务数据读取。 */
    private final WorkspaceRepository workspaceRepository;

    /** 创建 Workspace 仅针对当前默认组织，因此也必须校验该组织的 Owner 身份。 */
    private final OrganizationRepository organizationRepository;

    /** 通过构造器暴露全部授权依赖，方便用真实边界编写单元测试。 */
    public WorkspaceAccessService(
            WorkspaceService workspaceService,
            CurrentUser currentUser,
            AppUserRepository userRepository,
            OrganizationMemberRepository organizationMemberRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository) {
        this.workspaceService = workspaceService;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.organizationMemberRepository = organizationMemberRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.organizationRepository = organizationRepository;
    }

    /**
     * 校验当前用户可读取指定 Workspace，并返回实体供服务层继续使用内部键。
     */
    public Workspace requireRead(UUID workspacePublicId) {
        AccessContext context = resolve(workspacePublicId);
        if (context.role() != MemberRole.OWNER
                && workspaceMemberRepository.findByUserIdAndWorkspaceId(context.user().getId(), context.workspace().getId()).isEmpty()) {
            throw new WorkspaceAccessDeniedException(workspacePublicId);
        }
        return context.workspace();
    }

    /**
     * 校验当前用户可访问 Workspace 且角色处于命令允许集合中。
     */
    public Workspace requireRole(UUID workspacePublicId, MemberRole... allowedRoles) {
        Workspace workspace = requireRead(workspacePublicId);
        MemberRole actualRole = resolve(workspacePublicId).role();
        if (Arrays.stream(allowedRoles).noneMatch(actualRole::equals)) {
            throw new WorkspaceAccessDeniedException(workspacePublicId);
        }
        return workspace;
    }

    /**
     * 返回当前账户实际可见的 Workspace 列表。
     *
     * <p>Owner 可查看其所属组织的全部 Workspace；其他角色只能查看 workspace_member 中明确授权的记录。结果按创建时间倒序并按内部键去重，避免一个 Owner 同时持有显式范围时重复出现。</p>
     */
    public List<Workspace> listReadable() {
        AppUser user = userRepository.findByPublicId(currentUser.requirePublicId())
                .filter(found -> !found.isDisabled())
                .orElseThrow(AuthenticationRequiredException::new);
        List<OrganizationMember> organizationMembers = organizationMemberRepository.findByUserId(user.getId());
        List<Long> ownerOrganizationIds = organizationMembers.stream()
                .filter(member -> member.getRole() == MemberRole.OWNER)
                .map(OrganizationMember::getOrganizationId)
                .toList();
        List<Long> memberWorkspaceIds = workspaceMemberRepository.findByUserId(user.getId()).stream()
                .map(WorkspaceMember::getWorkspaceId)
                .toList();
        Stream<Workspace> ownerWorkspaces = ownerOrganizationIds.isEmpty()
                ? Stream.empty()
                : workspaceRepository.findByOrganizationIdInOrderByCreatedAtDesc(ownerOrganizationIds).stream();
        Stream<Workspace> memberWorkspaces = memberWorkspaceIds.isEmpty()
                ? Stream.empty()
                : workspaceRepository.findByIdInOrderByCreatedAtDesc(memberWorkspaceIds).stream();
        return Stream.concat(ownerWorkspaces, memberWorkspaces)
                .collect(java.util.stream.Collectors.toMap(Workspace::getId, workspace -> workspace, (first, ignored) -> first))
                .values().stream()
                .sorted(Comparator.comparing(Workspace::getCreatedAt).reversed())
                .toList();
    }

    /**
     * 当前创建路径尚未开放选择组织，因此只允许默认组织的 Owner 创建新的会话工作区。
     * 未来组织管理 API 落地后，应改为以路径中的公开组织 UUID 校验，而不是接受内部 ID。
     */
    public void requireCanCreateDefaultWorkspace() {
        AppUser user = userRepository.findByPublicId(currentUser.requirePublicId())
                .filter(found -> !found.isDisabled())
                .orElseThrow(AuthenticationRequiredException::new);
        Long organizationId = organizationRepository.findByDefaultOrganizationTrue()
                .orElseThrow(() -> new IllegalStateException("默认组织不存在，无法创建工作区"))
                .getId();
        OrganizationMember membership = organizationMemberRepository.findByUserIdAndOrganizationId(user.getId(), organizationId)
                .orElseThrow(() -> new WorkspaceAccessDeniedException(null));
        if (membership.getRole() != MemberRole.OWNER) {
            throw new WorkspaceAccessDeniedException(null);
        }
    }

    /**
     * 一次解析账号、组织和 Workspace 归属，保证后续检查来自同一可信范围。
     */
    private AccessContext resolve(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        AppUser user = userRepository.findByPublicId(currentUser.requirePublicId())
                .filter(found -> !found.isDisabled())
                .orElseThrow(AuthenticationRequiredException::new);
        OrganizationMember membership = organizationMemberRepository
                .findByUserIdAndOrganizationId(user.getId(), workspace.getOrganizationId())
                .orElseThrow(() -> new WorkspaceAccessDeniedException(workspacePublicId));
        return new AccessContext(workspace, user, membership.getRole());
    }

    /** 仅在当前授权调用中传递已校验的内部对象，不暴露给 Controller 或 API。 */
    private record AccessContext(Workspace workspace, AppUser user, MemberRole role) {
    }
}
