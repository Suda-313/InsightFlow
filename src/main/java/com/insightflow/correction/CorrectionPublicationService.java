package com.insightflow.correction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.ManualCorrection;
import com.insightflow.entity.RagEvaluationRun;
import com.insightflow.entity.Workspace;
import com.insightflow.evaluation.rag.RagEvaluationMetrics;
import com.insightflow.repository.ManualCorrectionRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.RagEvaluationRunRepository;
import com.insightflow.security.MemberRole;
import com.insightflow.security.WorkspaceAccessService;
import com.insightflow.service.AuditLogService;
import com.insightflow.service.EvaluationHistoryService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 双评测门禁后的纠错候选发布服务。 */
@Service
@Transactional
public class CorrectionPublicationService {
    /** Owner 是唯一可发布候选的角色。 */ private final WorkspaceAccessService accessService;
    /** 通用金标门禁复用既有评测历史和回归逻辑。 */ private final EvaluationHistoryService evaluationHistoryService;
    /** RAG 批次单独读取，避免混用两类指标口径。 */ private final RagEvaluationRunRepository ragRepository;
    /** 只解析脱敏指标 JSON。 */ private final ObjectMapper objectMapper;
    /** 纠错候选状态写入入口。 */ private final ManualCorrectionRepository correctionRepository;
    /** 发布结果必须留审计。 */ private final AuditLogService auditLogService;
    /** 通过公开调查 UUID 校验路径与候选的关联，阻断跨卡片审批。 */ private final InvestigationCaseRepository caseRepository;
    /** 显式注入门禁与状态边界。 */
    public CorrectionPublicationService(WorkspaceAccessService accessService, EvaluationHistoryService evaluationHistoryService, RagEvaluationRunRepository ragRepository, ObjectMapper objectMapper, ManualCorrectionRepository correctionRepository, AuditLogService auditLogService, InvestigationCaseRepository caseRepository) {
        this.accessService = accessService; this.evaluationHistoryService = evaluationHistoryService; this.ragRepository = ragRepository; this.objectMapper = objectMapper; this.correctionRepository = correctionRepository; this.auditLogService = auditLogService; this.caseRepository = caseRepository;
    }
    /** 任一金标或 RAG 指标回归即拒绝发布，候选保留在待复核状态。 */
    public ManualCorrection approve(UUID workspaceId, UUID caseId, UUID correctionId, UUID goldBaseline, UUID goldCandidate, UUID ragBaseline, UUID ragCandidate) {
        Workspace workspace = accessService.requireRole(workspaceId, MemberRole.OWNER);
        ManualCorrection correction = correctionRepository.findByWorkspaceIdAndPublicId(workspace.getId(), correctionId).orElseThrow(() -> new IllegalArgumentException("纠错候选不存在或不属于当前工作区"));
        Long caseInternalId = caseRepository.findByWorkspaceIdAndPublicId(workspace.getId(), caseId).orElseThrow(() -> new IllegalArgumentException("调查卡片不存在或不属于当前工作区")).getId();
        if (!caseInternalId.equals(correction.getInvestigationCaseId())) throw new IllegalArgumentException("纠错候选不属于当前调查卡片");
        if (!evaluationHistoryService.compare(workspaceId, goldCandidate, goldBaseline).gate().passed()) throw new EvaluationRegressionException("gold_evaluation_regressed");
        if (!ragPasses(workspace.getId(), ragBaseline, ragCandidate)) throw new EvaluationRegressionException("rag_evaluation_regressed");
        correction.markPublished();
        auditLogService.record(workspaceId, "correction.published", correction.getPublicId(), "kind=" + correction.getKind());
        return correction;
    }
    /** RAG 门禁允许召回和引用各最多下降 2%，不允许无依据回答率上升，样例数必须一致。 */
    private boolean ragPasses(Long workspaceId, UUID baselineId, UUID candidateId) {
        try {
            RagEvaluationRun baseline = ragRepository.findByPublicIdAndWorkspaceId(baselineId, workspaceId).orElseThrow(() -> new IllegalArgumentException("RAG 基线评测不存在"));
            RagEvaluationRun candidate = ragRepository.findByPublicIdAndWorkspaceId(candidateId, workspaceId).orElseThrow(() -> new IllegalArgumentException("RAG 候选评测不存在"));
            RagEvaluationMetrics left = objectMapper.readValue(baseline.getMetricsJson(), RagEvaluationMetrics.class);
            RagEvaluationMetrics right = objectMapper.readValue(candidate.getMetricsJson(), RagEvaluationMetrics.class);
            return right.caseCount() == left.caseCount() && right.retrievalRecallRate() >= left.retrievalRecallRate() - 0.02 && right.citationCorrectnessRate() >= left.citationCorrectnessRate() - 0.02 && right.ungroundedAnswerRate() <= left.ungroundedAnswerRate() + 0.01;
        } catch (java.io.IOException exception) { throw new EvaluationRegressionException("rag_metrics_unreadable"); }
    }
}
