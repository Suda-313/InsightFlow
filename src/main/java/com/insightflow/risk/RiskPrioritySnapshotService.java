package com.insightflow.risk;

import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.RiskPrioritySnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将新告警固化为可复核风险快照的唯一写入入口。 */
@Service
@Transactional
public class RiskPrioritySnapshotService {
    /** 读取已持久化的告警事实，不信任事件外的可变对象。 */
    private final AlertRepository alertRepository;
    /** 主题名称用于选择受控风险权重。 */
    private final IssueCatalogRepository issueCatalogRepository;
    /** 快照唯一约束是重复事件投递时的幂等底线。 */
    private final RiskPrioritySnapshotRepository snapshotRepository;
    /** 纯评分服务保持无副作用和易测试。 */
    private final RiskPriorityService priorityService;

    public RiskPrioritySnapshotService(AlertRepository alertRepository, IssueCatalogRepository issueCatalogRepository,
                                       RiskPrioritySnapshotRepository snapshotRepository, RiskPriorityService priorityService) {
        this.alertRepository = alertRepository;
        this.issueCatalogRepository = issueCatalogRepository;
        this.snapshotRepository = snapshotRepository;
        this.priorityService = priorityService;
    }

    /** 为指定告警创建或复用快照；事件重放不会改变已冻结的排序结果。 */
    public RiskPrioritySnapshot recordForAlert(Long workspaceId, Long alertId) {
        return snapshotRepository.findByWorkspaceIdAndAlertId(workspaceId, alertId).orElseGet(() -> {
            Alert alert = alertRepository.findById(alertId)
                    .filter(value -> workspaceId.equals(value.getWorkspaceId()))
                    .orElseThrow(() -> new IllegalArgumentException("告警不存在或不属于当前工作区"));
            IssueCatalog issue = issueCatalogRepository.findById(alert.getIssueId())
                    .filter(value -> workspaceId.equals(value.getWorkspaceId()))
                    .orElseThrow(() -> new IllegalArgumentException("告警主题不存在或不属于当前工作区"));
            RiskPriority priority = priorityService.score(alert, issueRiskWeight(issue.getCanonicalKey()), 0);
            return snapshotRepository.save(RiskPrioritySnapshot.create(workspaceId, alertId, priority));
        });
    }

    /** 首期仅用可审计的主题键区分资产和登录等高风险问题，未知主题采用保守默认值。 */
    private int issueRiskWeight(String canonicalKey) {
        if (canonicalKey != null && (canonicalKey.contains("login") || canonicalKey.contains("payment")
                || canonicalKey.contains("charge") || canonicalKey.contains("refund"))) {
            return 20;
        }
        if (canonicalKey != null && (canonicalKey.contains("crash") || canonicalKey.contains("stability"))) {
            return 15;
        }
        return 5;
    }
}
