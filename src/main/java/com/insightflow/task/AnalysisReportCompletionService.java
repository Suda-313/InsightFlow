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
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !task.isLeaseOwnedBy(workerId) || !"analysis_report".equals(task.getTaskType())) {
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
        report.markSucceeded(reportJson);
    }

    /**
     * 收敛分析报告失败状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID taskPublicId, String workerId, String code, String message) {
        AsyncTask task = taskRepository.findByPublicId(taskPublicId).orElse(null);
        if (task == null || !task.isLeaseOwnedBy(workerId) || !"analysis_report".equals(task.getTaskType())) {
            return;
        }
        task.markFailed(code, message);
        reportRepository.findByAsyncTaskIdAndWorkspaceId(task.getId(), task.getWorkspaceId())
                .ifPresent(report -> report.markFailed(code, message));
    }
}
