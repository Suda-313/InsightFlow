package com.insightflow.service.analysis;

import com.insightflow.entity.FeedbackReviewCandidate;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.FeedbackReviewCandidateRepository;
import com.insightflow.security.MemberRole;
import com.insightflow.security.WorkspaceAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 复核候选的唯一业务入口。
 *
 * <p>自动创建仅在投影事务内调用，人工确认与忽略必须有当前 Workspace 的 ANALYST 或
 * OWNER 权限。它不触碰规则文件、既有主题链接和统计表。</p>
 */
@Service
public class FeedbackReviewCandidateService {

    private final FeedbackReviewCandidateRepository repository;
    private final WorkspaceAccessService accessService;

    /** 显式注入持久化和权限边界，避免 Controller 直接操作实体。 */
    public FeedbackReviewCandidateService(FeedbackReviewCandidateRepository repository,
                                          WorkspaceAccessService accessService) {
        this.repository = repository;
        this.accessService = accessService;
    }

    /** 投影重试不会重复创建同一事件、同一原因的候选。 */
    @Transactional
    public void createIfNeeded(Long workspaceId, Long projectionId, Long eventId, String reasonCode,
                               String suggestedIssueKey, String suggestedSentiment) {
        if (repository.existsByWorkspaceProjectionIdAndFeedbackEventIdAndReasonCode(projectionId, eventId, reasonCode)) {
            return;
        }
        repository.save(FeedbackReviewCandidate.pending(workspaceId, eventId, projectionId, reasonCode,
                suggestedIssueKey, suggestedSentiment));
    }

    /** 页面读取先验证成员可读范围，返回的实体随后只映射为受控 API 字段。 */
    @Transactional(readOnly = true)
    public List<FeedbackReviewCandidate> pending(UUID workspacePublicId) {
        Workspace workspace = accessService.requireRead(workspacePublicId);
        return repository.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(workspace.getId(), "pending_review");
    }

    /** 人工确认表示接受当前候选用于后续规则/金标讨论，但不改变历史事实。 */
    @Transactional
    public FeedbackReviewCandidate confirm(UUID workspacePublicId, UUID candidatePublicId) {
        return candidateForWrite(workspacePublicId, candidatePublicId, true);
    }

    /** 人工忽略表示当前候选不采纳，仍保留状态和时间供审计查看。 */
    @Transactional
    public FeedbackReviewCandidate ignore(UUID workspacePublicId, UUID candidatePublicId) {
        return candidateForWrite(workspacePublicId, candidatePublicId, false);
    }

    /** 统一执行角色和 Workspace 隔离校验，再执行实体受限状态机。 */
    private FeedbackReviewCandidate candidateForWrite(UUID workspacePublicId, UUID candidatePublicId, boolean confirm) {
        Workspace workspace = accessService.requireRole(workspacePublicId, MemberRole.OWNER, MemberRole.ANALYST);
        FeedbackReviewCandidate candidate = repository.findByWorkspaceIdAndPublicId(workspace.getId(), candidatePublicId)
                .orElseThrow(() -> new IllegalArgumentException("复核候选不存在或不属于当前工作区"));
        if (confirm) {
            candidate.confirm();
        } else {
            candidate.ignore();
        }
        return candidate;
    }
}
