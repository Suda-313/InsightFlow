package com.insightflow.proposal;

import com.insightflow.entity.ActionProposal;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.ProposalAction;
import com.insightflow.repository.ActionProposalRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于已冻结调查证据生成最小、可解释的系统提案。
 *
 * <p>首版刻意只生成确认与忽略两个互斥的低风险流程建议，不让模型直接产生外部写操作。后续 Agent 可在相同提案契约中补充依据，但仍必须由 ProposalCommandService 预览、确认和审计。</p>
 */
@Service
@Transactional
public class ProposalDraftService {

    /** 提案仓储只承载待审建议，不承担执行状态写入。 */
    private final ActionProposalRepository proposalRepository;

    /** 构造器显式限制草案生成依赖，避免在此服务读取或修改 Alert。 */
    public ProposalDraftService(ActionProposalRepository proposalRepository) {
        this.proposalRepository = proposalRepository;
    }

    /**
     * 在调查进入待复核时一次性创建默认提案；重试时复用既有结果，避免重复出现在人工待办中。
     */
    public List<ActionProposal> ensureDefaultProposals(InvestigationCase investigation) {
        List<ActionProposal> existing = proposalRepository.findByWorkspaceIdAndInvestigationCaseIdOrderByCreatedAtAsc(
                investigation.getWorkspaceId(), investigation.getId());
        if (!existing.isEmpty()) {
            return existing;
        }
        ActionProposal confirm = ActionProposal.pending(
                investigation.getWorkspaceId(), investigation.getId(), ProposalAction.CONFIRM,
                "确认调查结论", "调查证据已冻结，需由人工确认其是否可进入后续复盘与评测。",
                "{\"effect\":\"investigation.status: pending_review -> confirmed\",\"writes\":false}");
        ActionProposal ignore = ActionProposal.pending(
                investigation.getWorkspaceId(), investigation.getId(), ProposalAction.IGNORE,
                "标记为无需处置", "若人工判断本次告警没有需要跟进的业务影响，可保留证据并标记忽略。",
                "{\"effect\":\"investigation.status: pending_review -> ignored\",\"writes\":false}");
        return proposalRepository.saveAll(List.of(confirm, ignore));
    }
}
