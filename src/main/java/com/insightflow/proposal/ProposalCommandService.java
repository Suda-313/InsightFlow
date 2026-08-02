package com.insightflow.proposal;

import com.insightflow.entity.ActionExecution;
import com.insightflow.entity.ActionProposal;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.ProposalAction;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.ActionExecutionRepository;
import com.insightflow.repository.ActionProposalRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.security.CurrentUser;
import com.insightflow.security.MemberRole;
import com.insightflow.security.WorkspaceAccessService;
import com.insightflow.service.AuditLogService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工确认、执行和撤销提案的唯一命令入口。
 *
 * <p>Agent 与 Controller 都不得直接修改调查状态：本服务统一执行角色校验、卡片/提案范围校验、幂等、状态迁移、执行记录和审计。动作仅影响 InvestigationCase，不会改写 Alert 或证据快照。</p>
 */
@Service
@Transactional
public class ProposalCommandService {

    /** 仅 Owner/Operator 可执行或撤销处置。 */
    private final WorkspaceAccessService accessService;

    /** 当前操作者来自安全上下文，不能由请求体伪造。 */
    private final CurrentUser currentUser;

    /** 调查状态迁移必须按 Workspace 二次过滤。 */
    private final InvestigationCaseRepository investigationCaseRepository;

    /** 提案只能属于当前调查，不能借公开 UUID 跨卡片执行。 */
    private final ActionProposalRepository proposalRepository;

    /** 幂等和撤销都以执行记录为事实来源。 */
    private final ActionExecutionRepository executionRepository;

    /** 每次真实执行或撤销都必须写入独立审计事实。 */
    private final AuditLogService auditLogService;

    /** 构造器将权限、身份、状态与审计边界显式收敛。 */
    public ProposalCommandService(
            WorkspaceAccessService accessService,
            CurrentUser currentUser,
            InvestigationCaseRepository investigationCaseRepository,
            ActionProposalRepository proposalRepository,
            ActionExecutionRepository executionRepository,
            AuditLogService auditLogService) {
        this.accessService = accessService;
        this.currentUser = currentUser;
        this.investigationCaseRepository = investigationCaseRepository;
        this.proposalRepository = proposalRepository;
        this.executionRepository = executionRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * 执行一次已预览的待审提案；相同幂等键重复调用直接返回第一次执行结果。
     */
    public ActionExecution execute(UUID workspacePublicId, UUID casePublicId, UUID proposalPublicId, String idempotencyKey) {
        Workspace workspace = accessService.requireRole(workspacePublicId, MemberRole.OWNER, MemberRole.OPERATOR);
        validateIdempotencyKey(idempotencyKey);
        ActionExecution existing = executionRepository
                .findByWorkspaceIdAndIdempotencyKey(workspace.getId(), idempotencyKey)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        InvestigationCase investigation = investigationCaseRepository.findByWorkspaceIdAndPublicId(workspace.getId(), casePublicId)
                .orElseThrow(() -> new IllegalArgumentException("调查卡片不存在或不属于当前工作区"));
        ActionProposal proposal = proposalRepository.findByWorkspaceIdAndPublicId(workspace.getId(), proposalPublicId)
                .filter(found -> investigation.getId().equals(found.getInvestigationCaseId()))
                .filter(found -> "pending".equals(found.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("处置提案不可执行"));
        applyAction(investigation, proposal.getAction());
        proposal.markExecuted();
        ActionExecution execution = executionRepository.save(ActionExecution.executed(
                workspace.getId(), investigation.getId(), proposal.getId(), currentUser.requirePublicId(), idempotencyKey,
                proposal.getAction(), "action=" + proposal.getAction()));
        auditLogService.record(workspacePublicId, "proposal.executed", execution.getPublicId(), "action=" + proposal.getAction());
        return execution;
    }

    /**
     * 撤销已执行动作并让调查回到待复核；不会删除执行记录、提案或原始告警。
     */
    public ActionExecution undo(UUID workspacePublicId, UUID casePublicId, UUID executionPublicId) {
        Workspace workspace = accessService.requireRole(workspacePublicId, MemberRole.OWNER, MemberRole.OPERATOR);
        InvestigationCase investigation = investigationCaseRepository.findByWorkspaceIdAndPublicId(workspace.getId(), casePublicId)
                .orElseThrow(() -> new IllegalArgumentException("调查卡片不存在或不属于当前工作区"));
        ActionExecution execution = executionRepository.findByWorkspaceIdAndPublicId(workspace.getId(), executionPublicId)
                .filter(found -> investigation.getId().equals(found.getInvestigationCaseId()))
                .orElseThrow(() -> new IllegalArgumentException("执行记录不存在或不属于当前调查"));
        if ("executed".equals(execution.getStatus())) {
            // 撤销必须同步恢复原提案，否则卡片会显示“待复核”却无法再次确认。
            ActionProposal proposal = proposalRepository.findById(execution.getActionProposalId())
                    .filter(found -> workspace.getId().equals(found.getWorkspaceId()))
                    .filter(found -> investigation.getId().equals(found.getInvestigationCaseId()))
                    .orElseThrow(() -> new IllegalStateException("执行记录关联的提案不存在或范围不一致"));
            investigation.reopenForReview();
            proposal.reopenForReview();
            execution.markUndone();
            auditLogService.record(workspacePublicId, "proposal.undone", execution.getPublicId(), "action=" + execution.getAction());
        }
        return execution;
    }

    /** 根据固定枚举进行最小状态迁移，不存在默认分支来隐藏未知动作。 */
    private void applyAction(InvestigationCase investigation, ProposalAction action) {
        switch (action) {
            case CONFIRM -> investigation.markConfirmed();
            case IGNORE -> investigation.markIgnored();
            case CLOSE -> investigation.markClosed();
        }
    }

    /** 幂等键为空或过长时拒绝，避免将同一命令误判为不同操作。 */
    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空且不能超过 200 个字符");
        }
    }
}
