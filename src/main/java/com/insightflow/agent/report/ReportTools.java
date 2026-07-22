package com.insightflow.agent.report;

import com.insightflow.entity.Alert;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.repository.AlertRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 报告工具：为 LLM 提供查询数据库的工具方法。
 */
@Component
public class ReportTools {

    private final IssueMetricBucketRepository issueMetricBucketRepository;
    private final AlertRepository alertRepository;

    public ReportTools(IssueMetricBucketRepository issueMetricBucketRepository,
                       AlertRepository alertRepository) {
        this.issueMetricBucketRepository = issueMetricBucketRepository;
        this.alertRepository = alertRepository;
    }

    /**
     * 查询某主题最近几天的指标趋势。
     */
    public List<IssueMetricBucket> getMetricTrend(String issueKey, int days) {
        return issueMetricBucketRepository.findAll()
                .stream()
                .limit(Math.max(0, days))
                .toList();
    }

    /**
     * 查询最近的告警记录。
     */
    public List<Alert> getRecentAlerts(int limit) {
        return alertRepository.findAll()
                .stream()
                .limit(Math.max(0, limit))
                .toList();
    }
}
