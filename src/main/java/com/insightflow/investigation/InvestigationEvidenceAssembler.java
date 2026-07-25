package com.insightflow.investigation;

import com.insightflow.agent.investigation.InvestigationEvidence;
import com.insightflow.agent.investigation.InvestigationIntent;
import com.insightflow.agent.investigation.InvestigationPlan;
import com.insightflow.agent.investigation.InvestigationToolService;
import com.insightflow.agent.investigation.InvestigationToolType;
import com.insightflow.entity.Alert;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.InvestigationEvidenceSnapshot;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.IssueCatalogRepository;
import com.insightflow.repository.WorkspaceRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 将告警事实与固定白名单 Tool 的结果转化为不可变调查证据快照。
 *
 * <p>装配器不调用 LLM，也不接受用户自定义 Tool、SQL、日期范围或 Prompt。它仅以触发告警所属主题构造固定调查计划，因此 Agent 后续只能引用同一批冻结证据而不能在任务中写库或扩展数据访问范围。</p>
 */
@Service
public class InvestigationEvidenceAssembler {

    /** 只读 Tool 服务负责逐个按 workspace_id 收敛数据读取。 */
    private final InvestigationToolService toolService;

    /** 用告警的内部主题键获取服务端维护的 canonical name，不从用户输入推断主题。 */
    private final IssueCatalogRepository issueCatalogRepository;

    /** 通过任务 Workspace 内部键反查公开 UUID，供 Tool 服务继续执行边界校验。 */
    private final WorkspaceRepository workspaceRepository;

    /** 构造器明确区分受控 Tool、主题目录与 Workspace 解析三类只读依赖。 */
    public InvestigationEvidenceAssembler(
            InvestigationToolService toolService,
            IssueCatalogRepository issueCatalogRepository,
            WorkspaceRepository workspaceRepository) {
        this.toolService = toolService;
        this.issueCatalogRepository = issueCatalogRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * 先冻结告警触发事实，再根据已解析主题执行固定四个只读 Tool。
     */
    public List<InvestigationEvidenceSnapshot> assemble(InvestigationCase investigation, Alert alert) {
        List<InvestigationEvidenceSnapshot> snapshots = new ArrayList<>();
        snapshots.add(alertSnapshot(investigation, alert));
        Workspace workspace = workspaceRepository.findById(investigation.getWorkspaceId()).orElse(null);
        IssueCatalog issue = issueCatalogRepository.findById(alert.getIssueId()).orElse(null);
        if (workspace == null || issue == null || !investigation.getWorkspaceId().equals(issue.getWorkspaceId())) {
            return snapshots;
        }
        InvestigationPlan plan = new InvestigationPlan(
                InvestigationIntent.ANOMALY_INVESTIGATION,
                List.of(
                        InvestigationToolType.ISSUE_TREND,
                        InvestigationToolType.ALERT_HISTORY,
                        InvestigationToolType.SAMPLE_FEEDBACK,
                        InvestigationToolType.PERIOD_COMPARISON));
        List<InvestigationEvidence> evidence = toolService.investigate(workspace.getPublicId(), issue.getCanonicalName(), plan).evidence();
        for (InvestigationEvidence item : evidence) {
            snapshots.add(InvestigationEvidenceSnapshot.capture(
                    investigation.getId(), investigation.getWorkspaceId(), item.tool().name(), item.id(),
                    cap(item.title(), 200), cap(item.content(), 4_000), item.sufficient(), item.sourceUrl()));
        }
        return snapshots;
    }

    /**
     * Alert 是不可变触发事实，故无论目录或指标是否可用都必须首先保留一条可独立复核的快照。
     */
    private InvestigationEvidenceSnapshot alertSnapshot(InvestigationCase investigation, Alert alert) {
        String content = String.format(
                "触发时间=%s；当前值=%d；EWMA=%.2f；标准差=%.2f；z-score=%.2f；生效阈值=%d；状态=%s",
                alert.getBucketStart(), alert.getCurrentCount(), alert.getBaselineEwma(), alert.getBaselineStddev(),
                alert.getZScore(), alert.getEffectiveThreshold(), alert.getStatus());
        return InvestigationEvidenceSnapshot.capture(
                investigation.getId(), investigation.getWorkspaceId(), "ALERT", alert.getPublicId().toString(),
                "告警触发快照", content, true, null);
    }

    /**
     * 进一步限制快照字段长度，防止未来新增 Tool 把过长文本写入调查表或前端卡片。
     */
    private String cap(String value, int maxLength) {
        if (value == null) {
            return "数据不可用";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }
}
