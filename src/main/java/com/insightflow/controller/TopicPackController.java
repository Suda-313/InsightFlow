package com.insightflow.controller;

import com.insightflow.service.WorkspaceTopicPackService;
import com.insightflow.service.analysis.TopicPackRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Topic Pack 读取与 Workspace 级绑定 API。
 *
 * <p>Pack 列表为全局只读；绑定/切换需 OPERATOR+，且只影响后续新投影的 L1 规则来源。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class TopicPackController {

    private final WorkspaceTopicPackService topicPackService;

    public TopicPackController(WorkspaceTopicPackService topicPackService) {
        this.topicPackService = topicPackService;
    }

    /** 列出 classpath 已注册的全部 Topic Pack。 */
    @GetMapping("/topic-packs")
    public List<TopicPackRegistry.TopicPackSummary> listPacks() {
        return topicPackService.listAvailablePacks();
    }

    /** 读取 Workspace 当前生效的 Pack（含默认回退说明）。 */
    @GetMapping("/workspaces/{workspaceId}/topic-pack")
    public WorkspaceTopicPackService.TopicPackBinding getWorkspacePack(@PathVariable UUID workspaceId) {
        return topicPackService.getBinding(workspaceId);
    }

    /** 绑定或切换 Workspace 的 Topic Pack（OPERATOR+）。 */
    @PutMapping("/workspaces/{workspaceId}/topic-pack")
    public WorkspaceTopicPackService.TopicPackBinding setWorkspacePack(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody SetTopicPackRequest request) {
        return topicPackService.bindPack(workspaceId, request.packId());
    }

    public record SetTopicPackRequest(@NotBlank @Size(max = 80) String packId) {
    }
}
