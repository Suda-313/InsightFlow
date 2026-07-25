package com.insightflow.report;

import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.InvestigationEvidenceSnapshot;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.InvestigationEvidenceSnapshotRepository;
import com.insightflow.service.WorkspaceService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告任务读取已确认调查的冻结证据，并投影为报告可引用的最小快照。
 *
 * <p>不读取 queued、failed 或 pending_review 调查，防止尚未经人工确认的 Agent/Tool 判断进入正式复盘；所有仓储查询均携带 workspace_id，报告不会借 case UUID 跨工作区读取。</p>
 */
@Service
@Transactional(readOnly = true)
public class OperationalReportEvidenceAssembler {
    /** 将公开 Workspace UUID 解析为可靠内部隔离键。 */ private final WorkspaceService workspaceService;
    /** 只筛选 confirmed 调查。 */ private final InvestigationCaseRepository caseRepository;
    /** 读取冻结快照而非重新调用 Tool。 */ private final InvestigationEvidenceSnapshotRepository evidenceRepository;
    /** 构造器显式限定报告证据只来自调查聚合。 */
    public OperationalReportEvidenceAssembler(WorkspaceService workspaceService, InvestigationCaseRepository caseRepository, InvestigationEvidenceSnapshotRepository evidenceRepository) {
        this.workspaceService = workspaceService; this.caseRepository = caseRepository; this.evidenceRepository = evidenceRepository;
    }
    /**
     * 生成当前范围的证据投影；版本复盘缺少版本事件时仍返回确认调查，但由报告模板明确标注不可推断版本因果。
     */
    public List<ReportEvidence> forScope(UUID workspacePublicId, OperationalReportScope scope) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        OperationalReportScope resolvedScope = scope == null ? OperationalReportScope.WEEKLY : scope;
        return caseRepository.findByWorkspaceIdAndStatusOrderByUpdatedAtDesc(workspace.getId(), "confirmed").stream()
                .filter(investigation -> belongsToScope(investigation, resolvedScope))
                .flatMap(investigation -> evidenceRepository.findByInvestigationCaseIdAndWorkspaceIdOrderByCreatedAtAsc(investigation.getId(), workspace.getId())
                        .stream().map(snapshot -> ReportEvidence.from(investigation, snapshot, resolvedScope)))
                .toList();
    }

    /** 日报和周报按调查最终更新时间切分；版本复盘缺少版本事件来源时保留全部已确认事实。 */
    private boolean belongsToScope(InvestigationCase investigation, OperationalReportScope scope) {
        OffsetDateTime updatedAt = investigation.getUpdatedAt();
        return switch (scope) {
            case DAILY -> !updatedAt.isBefore(OffsetDateTime.now().minusDays(1));
            case WEEKLY -> !updatedAt.isBefore(OffsetDateTime.now().minusDays(7));
            case VERSION_REVIEW -> true;
        };
    }
    /** 报告 JSON 只使用公开 UUID、冻结内容和时间，绝不暴露内部关系键。 */
    public record ReportEvidence(UUID investigationId, UUID evidenceId, String title, String content, boolean sufficient, OffsetDateTime capturedAt, String scope) {
        /** 将已隔离的领域实体投影为报告快照。 */
        static ReportEvidence from(InvestigationCase investigation, InvestigationEvidenceSnapshot snapshot, OperationalReportScope scope) {
            return new ReportEvidence(investigation.getPublicId(), snapshot.getPublicId(), snapshot.getTitle(), snapshot.getContent(), snapshot.isSufficient(), snapshot.getCreatedAt(), scope.name());
        }
    }
}
