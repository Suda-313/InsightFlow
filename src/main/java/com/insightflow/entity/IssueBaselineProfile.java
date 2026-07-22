package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;

/**
 * 每个主题一条可更新的 EWMA 基线；最后处理桶防止晚到历史数据静默污染 EWMA。
 *
 * <p>基于 V6 迁移的 issue_baseline_profile 表。EWMA 和方差状态由
 * {@link #updateEwma} 方法维护，每调用一次处理一个日指标桶。</p>
 *
 * <p>classification 字段在本期使用 {@code @Transient} 标记，因为 V6 迁移
 * 尚未包含该列；未来迁移添加列后应移除 {@code @Transient} 并改为
 * {@code @Column(name = "classification", length = 30)}。</p>
 */
@Entity
@Table(name = "issue_baseline_profile")
public class IssueBaselineProfile {

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

    /** 已参与 EWMA 计算的桶数。 */
    @Column(name = "active_buckets", nullable = false)
    private int activeBuckets;

    /** 当前 EWMA 值。 */
    @Column(name = "baseline_ewma", nullable = false)
    private double baselineEwma;

    /** 当前 EWMA 方差（用于计算标准差）。 */
    @Column(name = "baseline_variance", nullable = false)
    private double baselineVariance;

    /** 最后处理的桶起始时间，用于幂等判断。 */
    @Column(name = "last_processed_bucket")
    private OffsetDateTime lastProcessedBucket;

    /** 基线状态：baseline_building（建设中）/ active（已稳定）。 */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    /** 最近一次分类结果（如 normal / anomaly），由调用方根据 EWMA 阈值判定后写入。
     * 本期暂不持久化，待 V8 迁移添加列后移除 @Transient。 */
    @Transient
    private String classification;

    /** 记录首次创建时刻。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 记录最近一次更新的时刻。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 反射构造器；业务代码使用 {@link #create} 工厂方法。 */
    protected IssueBaselineProfile() {
    }

    /**
     * 创建一条新基线并处理第一个日指标桶。
     *
     * <p>初始 EWMA 设为 {@code todayCount}，方差为零，状态为
     * {@code baseline_building}。第一个桶计入 activeBuckets 但不触发
     * 状态切换（需要 {@code minHistoryDays} 个桶才能进入 {@code active}）。</p>
     *
     * @param workspaceId   一级租户隔离键
     * @param issueId       主题目录内部主键
     * @param bucketStart   第一个桶的起始时间
     * @param todayCount    第一个桶的反馈计数
     * @param minHistoryDays 稳定基线所需最少天数
     * @return 新建的基线
     */
    public static IssueBaselineProfile create(
            Long workspaceId, Long issueId, OffsetDateTime bucketStart,
            int todayCount, int minHistoryDays) {
        IssueBaselineProfile profile = new IssueBaselineProfile();
        OffsetDateTime now = OffsetDateTime.now();
        profile.workspaceId = workspaceId;
        profile.issueId = issueId;
        profile.activeBuckets = 1;
        profile.baselineEwma = todayCount;
        profile.baselineVariance = 0;
        profile.lastProcessedBucket = bucketStart;
        profile.status = "baseline_building";
        profile.createdAt = now;
        profile.updatedAt = now;
        return profile;
    }

    /**
     * 用新桶数据更新 EWMA 和方差。
     *
     * <p>如果 {@code bucketStart} 与 {@link #lastProcessedBucket} 相同
     * （同一桶重复提交），方法直接返回——这是幂等守卫。</p>
     *
     * <p>EWMA 更新公式（使用旧 EWMA 计算差值）：</p>
     * <pre>
     *   delta = todayCount - ewma_old
     *   ewma_new = ewma_old + alpha * delta
     *   var_new = (1 - alpha) * (var_old + alpha * delta * delta)
     * </pre>
     *
     * <p>当 {@code activeBuckets >= minHistoryDays} 且当前状态为
     * {@code baseline_building} 时，状态自动切换为 {@code active}。</p>
     *
     * @param alpha          EWMA 平滑因子（0 &lt; alpha &le; 1）
     * @param todayCount     当前桶的反馈计数
     * @param bucketStart    当前桶的起始时间
     * @param minHistoryDays 稳定基线所需最少天数
     * @param classification 本轮分类结果（调用方根据 EWMA 阈值判定后传入）
     */
    public void updateEwma(double alpha, int todayCount, OffsetDateTime bucketStart,
                           int minHistoryDays, String classification) {
        if (isSameBucket(bucketStart)) {
            return;
        }

        double delta = todayCount - this.baselineEwma;
        this.baselineEwma = this.baselineEwma + alpha * delta;
        this.baselineVariance = (1 - alpha) * (this.baselineVariance + alpha * delta * delta);
        this.activeBuckets++;
        this.lastProcessedBucket = bucketStart;

        if ("baseline_building".equals(this.status) && this.activeBuckets >= minHistoryDays) {
            this.status = "active";
        }

        this.classification = classification;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 判断给定的桶起始时间是否与最后处理的桶相同，用于幂等守卫。
     *
     * @param bucketStart 待检查的桶起始时间
     * @return 如果相同返回 true
     */
    public boolean isSameBucket(OffsetDateTime bucketStart) {
        return bucketStart != null && bucketStart.equals(this.lastProcessedBucket);
    }

    /**
     * 返回当前基线标准差（方差的平方根）。
     *
     * @return 标准差
     */
    public double baselineStddev() {
        return Math.sqrt(this.baselineVariance);
    }

    /**
     * 设置初始分类（在首次调用 {@link #updateEwma} 之前由调用方设定）。
     *
     * @param c 分类值
     */
    public void setInitialClassification(String c) {
        this.classification = c;
    }

    // --- getters ---

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getIssueId() {
        return issueId;
    }

    public int getActiveBuckets() {
        return activeBuckets;
    }

    public double getBaselineEwma() {
        return baselineEwma;
    }

    public double getBaselineVariance() {
        return baselineVariance;
    }

    public OffsetDateTime getLastProcessedBucket() {
        return lastProcessedBucket;
    }

    public String getStatus() {
        return status;
    }

    public String getClassification() {
        return classification;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}