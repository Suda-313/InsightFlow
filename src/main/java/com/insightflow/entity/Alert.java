package com.insightflow.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 当主题的 EWMA 基线偏离超过阈值时创建的预警记录。
 *
 * <p>预警冻结了触发时的原始值、基线快照和证据摘要，确保后续可完全追溯。
 * 每条预警创建后不可修改（所有关键字段均为 {@code updatable = false}），
 * 对应的 issue 在解决后可关闭预警。</p>
 */
@Entity
@Table(name = "alert")
public class Alert {

    /** 内部主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 全局唯一标识，使用时间有序 UUID 提升索引局部性。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 一级租户隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 关联 issue_catalog 内部主键。 */
    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    /** 触发预警的投影内部主键。 */
    @Column(name = "workspace_projection_id", nullable = false, updatable = false)
    private Long workspaceProjectionId;

    /** 触发预警的桶起始时间。 */
    @Column(name = "bucket_start", nullable = false, updatable = false)
    private OffsetDateTime bucketStart;

    /** 触发预警时的桶计数。 */
    @Column(name = "current_count", nullable = false, updatable = false)
    private int currentCount;

    /** 触发时的基线 EWMA 快照。 */
    @Column(name = "baseline_ewma", nullable = false, updatable = false)
    private double baselineEwma;

    /** 触发时的基线标准差快照。 */
    @Column(name = "baseline_stddev", nullable = false, updatable = false)
    private double baselineStddev;

    /** 触发时的 Z-Score（表示偏离基线多少个标准差）。 */
    @Column(name = "z_score", nullable = false, updatable = false)
    private double zScore;

    /** 生效阈值（使预警成立的最低计数，用于过滤噪声）。 */
    @Column(name = "effective_threshold", nullable = false, updatable = false)
    private int effectiveThreshold;

    /** 预警状态：active（活跃）/ resolved（已解决）。 */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    /** 证据摘要 JSON，包含触发预警的样本事件列表等。 */
    @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String evidenceJson;

    /** 记录首次创建时刻。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 记录最近一次更新的时刻。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 反射构造器；业务代码使用 {@link #active} 工厂方法。 */
    protected Alert() {
    }

    /**
     * 创建一条 active 状态的新预警。
     *
     * <p>publicId 使用 {@link UuidCreator#getTimeOrdered()} 生成时间有序 UUID，
     * 提升 B-tree 索引写入性能。所有关键字段在创建后不可修改，确保预警证据链完整。</p>
     *
     * @param workspaceId         一级租户隔离键
     * @param issueId             主题目录内部主键
     * @param projectionId        触发预警的投影内部主键
     * @param bucketStart         触发桶的起始时间
     * @param currentCount        触发桶的计数
     * @param baselineEwma        触发时的基线 EWMA
     * @param baselineStddev      触发时的基线标准差
     * @param zScore              触发时的 Z-Score
     * @param effectiveThreshold  生效阈值
     * @param evidenceJson        证据摘要 JSON 字符串
     * @return 新建的 active 预警
     */
    public static Alert active(
            Long workspaceId, Long issueId, Long projectionId,
            OffsetDateTime bucketStart, int currentCount,
            double baselineEwma, double baselineStddev,
            double zScore, int effectiveThreshold, String evidenceJson) {
        Alert alert = new Alert();
        OffsetDateTime now = OffsetDateTime.now();
        alert.publicId = UuidCreator.getTimeOrdered();
        alert.workspaceId = workspaceId;
        alert.issueId = issueId;
        alert.workspaceProjectionId = projectionId;
        alert.bucketStart = bucketStart;
        alert.currentCount = currentCount;
        alert.baselineEwma = baselineEwma;
        alert.baselineStddev = baselineStddev;
        alert.zScore = zScore;
        alert.effectiveThreshold = effectiveThreshold;
        alert.status = "active";
        alert.evidenceJson = evidenceJson;
        alert.createdAt = now;
        alert.updatedAt = now;
        return alert;
    }

    // --- getters ---

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getIssueId() {
        return issueId;
    }

    public Long getWorkspaceProjectionId() {
        return workspaceProjectionId;
    }

    public OffsetDateTime getBucketStart() {
        return bucketStart;
    }

    public int getCurrentCount() {
        return currentCount;
    }

    public double getBaselineEwma() {
        return baselineEwma;
    }

    public double getBaselineStddev() {
        return baselineStddev;
    }

    public double getZScore() {
        return zScore;
    }

    public int getEffectiveThreshold() {
        return effectiveThreshold;
    }

    public String getStatus() {
        return status;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}