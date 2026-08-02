package com.insightflow.investigation;

import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.security.CurrentUser;
import com.insightflow.security.MemberRole;
import com.insightflow.security.WorkspaceAccessService;
import com.insightflow.service.AuditLogService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 最小人工响应命令：记录有人开始跟进高风险异常，但不建立派单、交接或排他所有权。
 *
 * <p>跟进状态与异步调查状态正交；所有读取仍以 Workspace 过滤，
 * 操作人只从安全上下文取得，并以受控摘要写入审计日志。</p>
 */
@Service
@Transactional
public class FollowUpCommandService {

    /** 所有命令先校验 Workspace 与允许开始跟进的最小角色集合。 */
    private final WorkspaceAccessService accessService;
    /** 防止 Controller 伪造操作人。 */
    private final CurrentUser currentUser;
    /** 卡片读写始终携带内部 workspace_id。 */
    private final InvestigationCaseRepository investigationCaseRepository;
    /** 跟进动作属于业务审计事件，而不是页面本地状态。 */
    private final AuditLogService auditLogService;

    /** 构造器显式声明授权、身份、持久化和审计边界。 */
    public FollowUpCommandService(
            WorkspaceAccessService accessService,
            CurrentUser currentUser,
            InvestigationCaseRepository investigationCaseRepository,
            AuditLogService auditLogService) {
        this.accessService = accessService;
        this.currentUser = currentUser;
        this.investigationCaseRepository = investigationCaseRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * 首位成员开始跟进时写入响应事实；重复请求保持首位操作人不变，
     * 但仍安全返回同一张卡片，满足客户端重试语义。
     */
    public InvestigationCase start(UUID workspacePublicId, UUID casePublicId) {
        Workspace workspace = accessService.requireRole(
                workspacePublicId, MemberRole.OWNER, MemberRole.ANALYST, MemberRole.OPERATOR);
        InvestigationCase investigation = investigationCaseRepository
                .findByWorkspaceIdAndPublicId(workspace.getId(), casePublicId)
                .orElseThrow(() -> new IllegalArgumentException("调查卡片不存在或不属于当前工作区"));
        String before = investigation.getFollowUpStatus();
        investigation.startFollowUp(currentUser.requirePublicId());
        InvestigationCase saved = investigationCaseRepository.save(investigation);
        if ("awaiting_follow_up".equals(before)) {
            auditLogService.record(workspacePublicId, "investigation.follow_up_started", casePublicId, "follow_up=in_follow_up");
        }
        return saved;
    }
}
