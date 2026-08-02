package com.insightflow.service;

import com.insightflow.entity.Workspace;
import com.insightflow.repository.WorkspaceRepository;
import com.insightflow.security.MemberRole;
import com.insightflow.security.WorkspaceAccessService;
import com.insightflow.service.analysis.TopicPackLoader;
import com.insightflow.service.analysis.TopicPackRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace 级 Topic Pack 绑定用例：读取/切换当前 Pack，校验 Pack 已在 Registry 中注册。
 *
 * <p>切换 Pack 不触发自动重投影——运营需在切换后手动触发投影，使新规则写入后续 link；
 * 历史 link 保留旧 canonical_key，避免 silent 数据改写。</p>
 */
@Service
public class WorkspaceTopicPackService {

    private final WorkspaceAccessService accessService;
    private final WorkspaceRepository workspaceRepository;
    private final TopicPackRegistry topicPackRegistry;

    public WorkspaceTopicPackService(
            WorkspaceAccessService accessService,
            WorkspaceRepository workspaceRepository,
            TopicPackRegistry topicPackRegistry) {
        this.accessService = accessService;
        this.workspaceRepository = workspaceRepository;
        this.topicPackRegistry = topicPackRegistry;
    }

    /** 列出全部可用 Pack（全局目录，不按 Workspace 过滤）。 */
    @Transactional(readOnly = true)
    public List<TopicPackRegistry.TopicPackSummary> listAvailablePacks() {
        return topicPackRegistry.listSummaries();
    }

    /** 读取 Workspace 当前生效的 Pack 信息（含默认回退）。 */
    @Transactional(readOnly = true)
    public TopicPackBinding getBinding(UUID workspacePublicId) {
        Workspace workspace = accessService.requireRead(workspacePublicId);
        TopicPackLoader loader = topicPackRegistry.resolveForWorkspace(workspace);
        return new TopicPackBinding(
                loader.packId(),
                loader.packVersion(),
                loader.displayName(),
                workspace.getTopicPackId() != null,
                topicPackRegistry.defaultPackId());
    }

    /**
     * 绑定 Workspace 到指定 Pack；需要 OPERATOR 及以上角色。
     *
     * @param packId pack.toml 中的 pack_id，须已通过 TopicPackRegistry 校验
     */
    @Transactional
    public TopicPackBinding bindPack(UUID workspacePublicId, String packId) {
        accessService.requireRole(workspacePublicId, MemberRole.OWNER, MemberRole.OPERATOR);
        topicPackRegistry.requireByPackId(packId);
        Workspace workspace = accessService.requireRead(workspacePublicId);
        workspace.bindTopicPack(packId);
        workspaceRepository.save(workspace);
        TopicPackLoader loader = topicPackRegistry.requireByPackId(packId);
        return new TopicPackBinding(
                loader.packId(),
                loader.packVersion(),
                loader.displayName(),
                true,
                topicPackRegistry.defaultPackId());
    }

    /** Workspace 当前 Pack 绑定响应。 */
    public record TopicPackBinding(
            String packId,
            String packVersion,
            String displayName,
            boolean explicitlyBound,
            String defaultPackId) {
    }
}
