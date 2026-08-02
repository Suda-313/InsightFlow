package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 单 L2 类目单日的指标聚合桶，是 Dashboard 首屏 L2 趋势的事实来源。
 *
 * <p>与 {@link IssueMetricBucket} 同构，但按固定 primary_expression 枚举聚合，
 * 不需要类似 issue_catalog 的动态目录表——L2 是全平台共用的 5 类固定枚举，
 * 不会随 Workspace 或 Pack 变化。同一 (workspace_id, primary_expression, bucket_start)
 * 唯一，后续投影写同一日期同一类目时走 UPSERT 合并 feedback_count，而非重复 INSERT。</p>
 */
@Entity
@Table(name = "expression_metric_bucket")
public class ExpressionMetricBucket {

    /** 内部主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一级租户隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** L2 固定枚举键，如 expr_suggestion。 */
    @Column(name = "primary_expression", nullable = false, length = 30, updatable = false)
    private String primaryExpression;

    /** 日桶起点（UTC 00:00），按 occurred_at 截断，口径与 issue_metric_bucket 对齐。 */
    @Column(name = "bucket_start", nullable = false, updatable = false)
    private OffsetDateTime bucketStart;

    /** 当日该 L2 类目的反馈总数。 */
    @Column(name = "feedback_count", nullable = false)
    private int feedbackCount;

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
    protected ExpressionMetricBucket() {
    }

    /**
     * 创建新的日指标桶。
     *
     * @param workspaceId           一级租户隔离键
     * @param primaryExpression     L2 固定枚举键
     * @param bucketStart           日桶起点（UTC 00:00）
     * @param feedbackCount         当日反馈数
     * @param workspaceProjectionId 投影内部主键
     * @return 新建的日指标桶
     */
    public static ExpressionMetricBucket of(
            Long workspaceId, String primaryExpression, OffsetDateTime bucketStart,
            int feedbackCount, Long workspaceProjectionId) {
        ExpressionMetricBucket bucket = new ExpressionMetricBucket();
        OffsetDateTime now = OffsetDateTime.now();
        bucket.workspaceId = workspaceId;
        bucket.primaryExpression = primaryExpression;
        bucket.bucketStart = bucketStart;
        bucket.feedbackCount = feedbackCount;
        bucket.workspaceProjectionId = workspaceProjectionId;
        bucket.createdAt = now;
        bucket.updatedAt = now;
        return bucket;
    }

    /**
     * 对已有桶追加本轮投影的反馈计数；不改变 bucketStart/createdAt。
     *
     * @param deltaCount   本轮新增反馈数
     * @param projectionId 本轮投影内部主键
     */
    public void addFeedbackCount(int deltaCount, Long projectionId) {
        this.feedbackCount += deltaCount;
        this.workspaceProjectionId = projectionId;
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getPrimaryExpression() { return primaryExpression; }
    public OffsetDateTime getBucketStart() { return bucketStart; }
    public int getFeedbackCount() { return feedbackCount; }
    public Long getWorkspaceProjectionId() { return workspaceProjectionId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
