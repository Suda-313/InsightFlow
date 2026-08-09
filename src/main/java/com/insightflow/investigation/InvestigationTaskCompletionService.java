package com.insightflow.investigation;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.proposal.ProposalDraftService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立短事务中收敛调查任务与卡片终态。
 *
 * <p>Worker 的 Tool 调用、快照装配和数据库写入若失败，终态仍需可靠落库；该服务复用任务租约校验，旧 Worker 不能覆盖已被重新领取的任务。</p>
 */
@Service
public class InvestigationTaskCompletionService {

    /** 任务状态由同一异步任务表统一维护。 */
    private final AsyncTaskRepository asyncTaskRepository;

    /** 调查卡片终态与任务终态必须在同一事务内更新。 */
    private final InvestigationCaseRepository investigationCaseRepository;

    /** 调查成功后仅生成待审草案，绝不替代人工执行。 */
    private final ProposalDraftService proposalDraftService;

    /** 构造器显式组合任务与卡片两个聚合，避免 Worker 直接散落更新。 */
    public InvestigationTaskCompletionService(
            AsyncTaskRepository asyncTaskRepository,
            InvestigationCaseRepository investigationCaseRepository,
            ProposalDraftService proposalDraftService) {
        this.asyncTaskRepository = asyncTaskRepository;
        this.investigationCaseRepository = investigationCaseRepository;
        this.proposalDraftService = proposalDraftService;
    }

    /**
     * 快照冻结成功后将卡片置为待人工复核，绝不自动确认或执行处置。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId, int evidenceCount) {
        complete(taskPublicId, workerId, -1, evidenceCount);
    }

    /** 快照完成受执行版本围栏保护，旧 owner 不能关闭已被重新领取的调查卡片。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId, int executionVersion, int evidenceCount) {
        AsyncTask task = asyncTaskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !"investigation".equals(task.getTaskType()) || !ownsLease(task, workerId, executionVersion)) {
            return;
        }
        InvestigationCase investigation = investigationCaseRepository
                .findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                .orElse(null);
        if (investigation == null) {
            task.markFailed("INVESTIGATION_CASE_NOT_FOUND", "调查卡片不存在或不属于当前工作区");
            return;
        }
        investigation.markPendingReview("已冻结 " + evidenceCount + " 条受控证据，等待人工复核");
        proposalDraftService.ensureDefaultProposals(investigation);
        task.markSucceeded("{\"investigation_case\":\"" + investigation.getPublicId() + "\"}");
    }

    /**
     * Tool 或快照装配失败只向前端暴露受控码和摘要，堆栈留在 Worker 日志。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId, String code, String message) {
        fail(taskPublicId, workerId, -1, code, message);
    }

    /** 超时结果只属于观测到它的执行版本，不能覆盖后续重新领取的调查。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId, int executionVersion, String code, String message) {
        AsyncTask task = asyncTaskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !"investigation".equals(task.getTaskType()) || !ownsLease(task, workerId, executionVersion)) {
            return;
        }
        task.markFailed(code, message);
        investigationCaseRepository.findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                .ifPresent(investigation -> investigation.markFailed(code, message));
    }

    private boolean ownsLease(AsyncTask task, String workerId, int executionVersion) {
        return executionVersion < 0 ? task.isLeaseOwnedBy(workerId) : task.isLeaseOwnedBy(workerId, executionVersion);
    }
}
