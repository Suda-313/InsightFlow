package com.insightflow.report;

import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import com.insightflow.risk.RiskLevel;
import com.insightflow.risk.RiskPrioritySnapshot;
import com.insightflow.service.WorkspaceService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将指定区间内新创建告警的风险快照投影为报告输入。
 * 风险等级与原因来自告警创建当刻冻结的快照，绝不按当前策略重新打分。
 */
@Service
@Transactional(readOnly = true)
public class OperationalReportRiskAssembler {

    private final WorkspaceService workspaceService;
    private final AlertRepository alertRepository;
    private final RiskPrioritySnapshotRepository snapshotRepository;
    private final IssueCatalogRepository issueCatalogRepository;

    public OperationalReportRiskAssembler(
            WorkspaceService workspaceService,
            AlertRepository alertRepository,
            RiskPrioritySnapshotRepository snapshotRepository,
            IssueCatalogRepository issueCatalogRepository) {
        this.workspaceService = workspaceService;
        this.alertRepository = alertRepository;
        this.snapshotRepository = snapshotRepository;
        this.issueCatalogRepository = issueCatalogRepository;
    }

    /** 返回范围内同时具有主题和冻结风险快照的告警；不完整的孤儿记录不写进正式报告。 */
    public List<ReportRisk> forTimeRange(UUID workspacePublicId, OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("报告时间范围必须为有效的左闭右开区间");
        }
        Workspace workspace = workspaceService.get(workspacePublicId);
        return alertRepository.findByWorkspaceIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        workspace.getId(), start, end)
                .stream()
                .map(alert -> toRisk(workspace.getId(), alert))
                .filter(risk -> risk != null)
                .toList();
    }

    private ReportRisk toRisk(Long workspaceId, Alert alert) {
        RiskPrioritySnapshot snapshot = snapshotRepository.findByWorkspaceIdAndAlertId(workspaceId, alert.getId())
                .orElse(null);
        IssueCatalog issue = issueCatalogRepository.findById(alert.getIssueId())
                .filter(value -> workspaceId.equals(value.getWorkspaceId()))
                .orElse(null);
        if (snapshot == null || issue == null) {
            return null;
        }
        return new ReportRisk(alert.getPublicId(), snapshot.getLevel(), snapshot.getScore(), snapshot.getReasons(),
                issue.getCanonicalKey(), issue.getCanonicalName(), alert.getCurrentCount(), alert.getCreatedAt());
    }

    /** 供报告 JSON 和 Agent 使用的脱敏风险摘要，不包含内部关联键或原始反馈。 */
    public record ReportRisk(
            UUID alertId,
            RiskLevel level,
            int score,
            String reasons,
            String issueKey,
            String issueName,
            int currentCount,
            OffsetDateTime createdAt) {
    }
}
