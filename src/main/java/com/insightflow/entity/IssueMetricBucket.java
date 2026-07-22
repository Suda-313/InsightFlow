package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 单主题单日的指标聚合桶；是看板趋势、报告和 EWMA 的共同事实来源。
 *
 * <p>同一 (workspace_id, issue_id, bucket_start) 唯一，后续投影写同一日期
 * 同一主题时走 UPSERT 合并 feedback_count 与 dimension_summary_json，
 * 而非重复 INSERT——这是日指标幂等的物理基础。</p>
 *
 * <p>dimension_summary_json 只统计 source_kind 分布（如 {"工单":12,"评价":5}），
 * 不展开 dimension_json 中的其他维度，避免不同 Workspace 的异构维度污染聚合。</p>
 */
@Entity
@Table(name = "issue_metric_bucket")
public class IssueMetricBucket {

    /** 内部主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一级租户隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 关联 issue_catalog 内部主键。 */
    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    /** 日桶起点（UTC 00:00），按 occurred_at 截断。 */
    @Column(name = "bucket_start", nullable = false, updatable = false)
    private OffsetDateTime bucketStart;

    /** 当日该主题的反馈总数。 */
    @Column(name = "feedback_count", nullable = false)
    private int feedbackCount;

    /** JSONB 维度摘要，如 {"工单":12,"评价":5}。 */
    @Column(name = "dimension_summary_json", nullable = false, columnDefinition = "jsonb")
    private String dimensionSummaryJson;

    /** 最后一次更新此桶的投影内部主键，用于审计追溯。 */
    @Column(name = "workspace_projection_id", nullable = false)
    private Long workspaceProjectionId;

    /** 记录首次写入时刻。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 记录最近一次更新的时刻。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 反射构造器；业务代码使用 {@link #of} 或 {@link #addFeedbackCount}。 */
    protected IssueMetricBucket() {
    }

    /**
     * 创建新的日指标桶；首次写入时 feedback_count 和 dimension_summary_json
     * 来自本轮聚合，workspaceProjectionId 记录产生此桶的投影。
     *
     * @param workspaceId           一级租户隔离键
     * @param issueId               主题目录内部主键
     * @param bucketStart           日桶起点（UTC 00:00）
     * @param feedbackCount         当日反馈数
     * @param dimensionSummaryJson  维度摘要 JSON
     * @param workspaceProjectionId 投影内部主键
     * @return 新建的日指标桶
     */
    public static IssueMetricBucket of(
            Long workspaceId, Long issueId, OffsetDateTime bucketStart,
            int feedbackCount, String dimensionSummaryJson, Long workspaceProjectionId) {
        IssueMetricBucket bucket = new IssueMetricBucket();
        OffsetDateTime now = OffsetDateTime.now();
        bucket.workspaceId = workspaceId;
        bucket.issueId = issueId;
        bucket.bucketStart = bucketStart;
        bucket.feedbackCount = feedbackCount;
        bucket.dimensionSummaryJson = dimensionSummaryJson;
        bucket.workspaceProjectionId = workspaceProjectionId;
        bucket.createdAt = now;
        bucket.updatedAt = now;
        return bucket;
    }

    /**
     * 对已有桶追加本轮投影的反馈计数与维度分布；不改变 bucketStart/createdAt。
     *
     * @param deltaCount      本轮新增反馈数
     * @param mergedSummaryJson 合并后的维度摘要 JSON
     * @param projectionId    本轮投影内部主键
     */
    public void addFeedbackCount(int deltaCount, String mergedSummaryJson, Long projectionId) {
        this.feedbackCount += deltaCount;
        this.dimensionSummaryJson = mergedSummaryJson;
        this.workspaceProjectionId = projectionId;
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getIssueId() { return issueId; }
    public OffsetDateTime getBucketStart() { return bucketStart; }
    public int getFeedbackCount() { return feedbackCount; }
    public String getDimensionSummaryJson() { return dimensionSummaryJson; }
    public Long getWorkspaceProjectionId() { return workspaceProjectionId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    /** 最近一次更新的时刻，用于外部检测陈旧桶。 */
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}