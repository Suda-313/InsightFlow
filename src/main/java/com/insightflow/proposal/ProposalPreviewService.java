package com.insightflow.proposal;

import com.insightflow.entity.ActionProposal;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.ActionProposalRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.security.WorkspaceAccessService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提案只读预览服务，先让人工看到状态影响再决定是否执行。
 */
@Service
@Transactional(readOnly = true)
public class ProposalPreviewService {

    /** 预览也必须经过当前 Workspace 成员范围检查。 */
    private final WorkspaceAccessService accessService;

    /** 卡片与提案分别按 Workspace 范围查询，不能只依赖 URL 中的 UUID。 */
    private final InvestigationCaseRepository investigationCaseRepository;

    /** 提案内容来自服务端冻结的预览 JSON，不接受客户端自定义。 */
    private final ActionProposalRepository proposalRepository;

    /** 构造器显式声明预览没有任何写入依赖。 */
    public ProposalPreviewService(
            WorkspaceAccessService accessService,
            InvestigationCaseRepository investigationCaseRepository,
            ActionProposalRepository proposalRepository) {
        this.accessService = accessService;
        this.investigationCaseRepository = investigationCaseRepository;
        this.proposalRepository = proposalRepository;
    }

    /**
     * 返回指定调查的待审提案预览；提案与调查不匹配时统一拒绝，阻断跨卡片引用。
     */
    public ProposalPreview preview(UUID workspacePublicId, UUID casePublicId, UUID proposalPublicId) {
        Workspace workspace = accessService.requireRead(workspacePublicId);
        InvestigationCase investigation = investigationCaseRepository.findByWorkspaceIdAndPublicId(workspace.getId(), casePublicId)
                .orElseThrow(() -> new IllegalArgumentException("调查卡片不存在或不属于当前工作区"));
        ActionProposal proposal = proposalRepository.findByWorkspaceIdAndPublicId(workspace.getId(), proposalPublicId)
                .filter(found -> investigation.getId().equals(found.getInvestigationCaseId()))
                .orElseThrow(() -> new IllegalArgumentException("处置提案不存在或不属于当前调查"));
        return new ProposalPreview(proposal.getPublicId(), proposal.getAction(), proposal.getTitle(), proposal.getRationale(), proposal.getPreviewJson());
    }

    /** 面向 HTTP 的只读投影，不暴露内部主键或幂等键。 */
    public record ProposalPreview(UUID proposalId, com.insightflow.entity.ProposalAction action, String title, String rationale, String previewJson) {
    }
}
