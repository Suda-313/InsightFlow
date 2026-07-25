package com.insightflow.correction;

import com.insightflow.entity.CorrectionKind;
import com.insightflow.entity.ManualCorrection;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.ManualCorrectionRepository;
import com.insightflow.security.CurrentUser;
import com.insightflow.security.MemberRole;
import com.insightflow.security.WorkspaceAccessService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 人工纠错候选提交入口；提交不等同发布。 */
@Service
@Transactional
public class CorrectionCommandService {
    /** ANALYST 可提候选，Owner 可管理发布；两者都需范围授权。 */ private final WorkspaceAccessService accessService;
    /** 提交人从安全上下文获取。 */ private final CurrentUser currentUser;
    /** 调查关联按 Workspace 验证。 */ private final InvestigationCaseRepository caseRepository;
    /** 候选写入入口。 */ private final ManualCorrectionRepository correctionRepository;
    /** 显式注入权限、身份、调查和持久化边界。 */
    public CorrectionCommandService(WorkspaceAccessService accessService, CurrentUser currentUser, InvestigationCaseRepository caseRepository, ManualCorrectionRepository correctionRepository) {
        this.accessService = accessService; this.currentUser = currentUser; this.caseRepository = caseRepository; this.correctionRepository = correctionRepository;
    }
    /** 提交限长、非空候选；不接收原始反馈或自由 JSON。 */
    public ManualCorrection submit(UUID workspaceId, UUID caseId, CorrectionKind kind, String content) {
        Workspace workspace = accessService.requireRole(workspaceId, MemberRole.OWNER, MemberRole.ANALYST);
        Long caseInternalId = caseId == null ? null : caseRepository.findByWorkspaceIdAndPublicId(workspace.getId(), caseId)
                .orElseThrow(() -> new IllegalArgumentException("调查卡片不存在或不属于当前工作区")).getId();
        if (kind == null || content == null || content.isBlank() || content.length() > 2000 || content.indexOf('{') >= 0) throw new IllegalArgumentException("纠错内容必须是 1 到 2000 字的受控文本");
        return correctionRepository.save(ManualCorrection.pending(workspace.getId(), caseInternalId, kind, content.trim(), currentUser.requirePublicId()));
    }
}
