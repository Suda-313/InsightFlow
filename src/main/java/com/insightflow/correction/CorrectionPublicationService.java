package com.insightflow.correction;

import com.insightflow.entity.ManualCorrection;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.ManualCorrectionRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.security.MemberRole;
import com.insightflow.security.WorkspaceAccessService;
import com.insightflow.service.AuditLogService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 双评测门禁后的纠错候选发布服务。 */
@Service
@Transactional
public class CorrectionPublicationService {
    /** Owner 是唯一可发布候选的角色。 */ private final WorkspaceAccessService accessService;
    /** 纠错候选状态写入入口。 */ private final ManualCorrectionRepository correctionRepository;
    /** 发布结果必须留审计。 */ private final AuditLogService auditLogService;
    /** 通过公开调查 UUID 校验路径与候选的关联，阻断跨卡片审批。 */ private final InvestigationCaseRepository caseRepository;
    /** 显式注入门禁与状态边界。 */
    public CorrectionPublicationService(WorkspaceAccessService accessService, ManualCorrectionRepository correctionRepository, AuditLogService auditLogService, InvestigationCaseRepository caseRepository) {
        this.accessService = accessService; this.correctionRepository = correctionRepository; this.auditLogService = auditLogService; this.caseRepository = caseRepository;
    }
    /** 任一金标或 RAG 指标回归即拒绝发布，候选保留在待复核状态。 */
    public ManualCorrection approve(UUID workspaceId, UUID caseId, UUID correctionId) {
        Workspace workspace = accessService.requireRole(workspaceId, MemberRole.OWNER);
        ManualCorrection correction = correctionRepository.findByWorkspaceIdAndPublicId(workspace.getId(), correctionId).orElseThrow(() -> new IllegalArgumentException("纠错候选不存在或不属于当前工作区"));
        Long caseInternalId = caseRepository.findByWorkspaceIdAndPublicId(workspace.getId(), caseId).orElseThrow(() -> new IllegalArgumentException("调查卡片不存在或不属于当前工作区")).getId();
        if (!caseInternalId.equals(correction.getInvestigationCaseId())) throw new IllegalArgumentException("纠错候选不属于当前调查卡片");
        correction.markPublished();
        auditLogService.record(workspaceId, "correction.published", correction.getPublicId(), "kind=" + correction.getKind());
        return correction;
    }
}
