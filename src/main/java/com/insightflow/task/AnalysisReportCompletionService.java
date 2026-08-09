package com.insightflow.task;

import com.insightflow.entity.AnalysisReport;
import com.insightflow.entity.AsyncTask;
import com.insightflow.repository.AnalysisReportRepository;
import com.insightflow.repository.AsyncTaskRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立短事务中收敛分析报告终态。
 */
@Service
public class AnalysisReportCompletionService {

    private final AsyncTaskRepository taskRepository;
    private final AnalysisReportRepository reportRepository;

    public AnalysisReportCompletionService(AsyncTaskRepository taskRepository, AnalysisReportRepository reportRepository) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
    }

    /**
     * 成功关闭分析报告任务，并将生成结果写入报告实体。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId, String reportJson) {
        complete(taskPublicId, workerId, reportJson, null);
    }

    /**
     * 成功关闭报告任务时一并保存冻结证据，保证报告正文和其依据在同一终态事务中落库。
     * 保留三参数重载给既有调用方，避免本次范围外的异步任务测试和调用受到影响。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId, String reportJson, String evidenceJson) {
        complete(taskPublicId, workerId, -1, reportJson, evidenceJson);
    }

    /** 报告内容仅允许由生成它的同一租约执行版本写入，防止旧 Worker 覆盖已重试结果。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID taskPublicId, String workerId, int executionVersion, String reportJson, String evidenceJson) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !ownsLease(task, workerId, executionVersion) || !"analysis_report".equals(task.getTaskType())) {
            return;
        }
        AnalysisReport report = reportRepository
                .findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                .orElse(null);
        if (report == null) {
            task.markFailed("REPORT_RECORD_NOT_FOUND", "报告记录不存在。");
            return;
        }
        task.markSucceeded("{\"report\":\"" + report.getPublicId() + "\"}");
        if (evidenceJson != null) {
            report.setReportEvidenceJson(evidenceJson);
        }
        report.markSucceeded(reportJson);
    }

    /**
     * 收敛分析报告失败状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId, String code, String message) {
        fail(taskPublicId, workerId, -1, code, message);
    }

    /** 已过期的报告 Worker 不得将被重新领取任务标记为失败。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId, int executionVersion, String code, String message) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !ownsLease(task, workerId, executionVersion) || !"analysis_report".equals(task.getTaskType())) {
            return;
        }
        task.markFailed(code, message);
        reportRepository.findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                .ifPresent(report -> report.markFailed(code, message));
    }

    private boolean ownsLease(AsyncTask task, String workerId, int executionVersion) {
        return executionVersion < 0 ? task.isLeaseOwnedBy(workerId) : task.isLeaseOwnedBy(workerId, executionVersion);
    }
}
