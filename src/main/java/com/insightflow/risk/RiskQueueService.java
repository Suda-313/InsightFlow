package com.insightflow.risk;

import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.security.WorkspaceAccessService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 为首页和调查中心读取“今日优先处理什么”的 Workspace 隔离风险队列。 */
@Service
@Transactional(readOnly = true)
public class RiskQueueService {
    /** 队列读取先通过服务层授权，防止仅凭 UUID 枚举另一个工作区的风险。 */
    private final WorkspaceAccessService accessService;
    /** 快照排序是唯一排序事实，不重新计算历史分数。 */
    private final RiskPrioritySnapshotRepository snapshotRepository;
    /** 告警提供触发规模和创建时间。 */
    private final AlertRepository alertRepository;
    /** 主题目录提供运营可读名称。 */
    private final IssueCatalogRepository issueCatalogRepository;

    public RiskQueueService(WorkspaceAccessService accessService, RiskPrioritySnapshotRepository snapshotRepository,
                            AlertRepository alertRepository, IssueCatalogRepository issueCatalogRepository) {
        this.accessService = accessService;
        this.snapshotRepository = snapshotRepository;
        this.alertRepository = alertRepository;
        this.issueCatalogRepository = issueCatalogRepository;
    }

    /** 返回仍能找到其原始告警和主题的快照，孤立数据不应伪装成可操作风险。 */
    public List<RiskQueueItem> list(UUID workspacePublicId) {
        Workspace workspace = accessService.requireRead(workspacePublicId);
        return snapshotRepository.findByWorkspaceIdOrderByScoreDescCreatedAtDesc(workspace.getId()).stream()
                .map(snapshot -> toItem(workspace.getId(), snapshot))
                .filter(item -> item != null)
                .toList();
    }

    /** 每项保持公共 UUID、解释原因和受控统计字段，不向前端泄露内部键。 */
    private RiskQueueItem toItem(Long workspaceId, RiskPrioritySnapshot snapshot) {
        Alert alert = alertRepository.findById(snapshot.getAlertId())
                .filter(value -> workspaceId.equals(value.getWorkspaceId())).orElse(null);
        if (alert == null) return null;
        IssueCatalog issue = issueCatalogRepository.findById(alert.getIssueId())
                .filter(value -> workspaceId.equals(value.getWorkspaceId())).orElse(null);
        if (issue == null) return null;
        return new RiskQueueItem(alert.getPublicId(), snapshot.getLevel(), snapshot.getScore(), snapshot.getReasons(),
                issue.getCanonicalKey(), issue.getCanonicalName(), alert.getCurrentCount(), alert.getCreatedAt());
    }

    /** 风险队列 HTTP 契约。 */
    public record RiskQueueItem(UUID alertId, RiskLevel level, int score, String reasons, String issueKey,
                                String issueName, int currentCount, OffsetDateTime createdAt) { }
}
