package com.insightflow.evaluation.rag;

import com.insightflow.entity.AsyncTask;
import com.insightflow.repository.AsyncTaskRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 持久化 RAG 任务的领取、查询和终态收敛边界。*/
@Service
public class RagEvaluationTaskService {
    private final AsyncTaskRepository tasks;
    private final long leaseSeconds;

    /** 任务只复用通用表的租约能力，类型过滤保证不会误领导入或报告任务。*/
    public RagEvaluationTaskService(AsyncTaskRepository tasks,
                                    @Value("${insightflow.evaluation.rag.lease-seconds:720}") long leaseSeconds) {
        this.tasks = tasks;
        this.leaseSeconds = leaseSeconds;
    }

    /** 在短事务内领取一条可恢复任务，超过最大尝试次数时收敛为固定错误。*/
    @Transactional
    public Optional<UUID> claimNext(String workerId) {
        AsyncTask task = tasks.findNextClaimableTaskByType("rag_evaluation", OffsetDateTime.now()).orElse(null);
        if (task == null || !task.hasAttemptsRemaining()) {
            if (task != null) task.markFailed("RAG_EVALUATION_RETRY_EXHAUSTED", "评测重试次数已耗尽");
            return Optional.empty();
        }
        task.claim(workerId, OffsetDateTime.now().plusSeconds(leaseSeconds));
        return Optional.of(task.getPublicId());
    }

    /** 查询时必须同时限制公开任务 UUID、Workspace 内部键和任务类型，防止跨工作区探测。*/
    @Transactional(readOnly = true)
    public Optional<AsyncTask> findWorkspaceTask(Long workspaceId, UUID taskPublicId) {
        return tasks.findByPublicId(taskPublicId)
                .filter(task -> workspaceId.equals(task.getWorkspaceId()))
                .filter(task -> "rag_evaluation".equals(task.getTaskType()));
    }

    /** 完成只接受仍持有租约的 Worker，过期实例不能覆盖新的执行结果。*/
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID taskPublicId, String workerId, UUID runPublicId) {
        tasks.findByPublicId(taskPublicId)
                .filter(task -> task.isLeaseOwnedBy(workerId))
                .filter(task -> "rag_evaluation".equals(task.getTaskType()))
                .ifPresent(task -> task.markSucceeded("{\"run\":\"" + runPublicId + "\"}"));
    }

    /** Worker 级异常只保留固定错误码和摘要，供应商异常原文仅写服务器日志。*/
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId) {
        tasks.findByPublicId(taskPublicId)
                .filter(task -> task.isLeaseOwnedBy(workerId))
                .filter(task -> "rag_evaluation".equals(task.getTaskType()))
                .ifPresent(task -> task.markFailed("RAG_EVALUATION_FAILED", "RAG 评测执行失败，请稍后重试"));
    }

    /** 全部题目无法得到可评分结果时保留任务摘要，但不伪造成功基线或运行 UUID。*/
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPartialFailed(UUID taskPublicId, String workerId) {
        tasks.findByPublicId(taskPublicId)
                .filter(task -> task.isLeaseOwnedBy(workerId))
                .filter(task -> "rag_evaluation".equals(task.getTaskType()))
                .ifPresent(task -> task.markPartialFailed("{\"reason\":\"NO_EVALUABLE_CASE\"}"));
    }
}
