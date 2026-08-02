package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 一次投影内的一个 Data Cell；控制粒度并为后续受限 LLM 分类提供固定证据边界。
 */
@Entity
@Table(name = "data_cell")
public class DataCell {

    /**
     * 内部主键。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 一级租户隔离键。
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * 所属投影内部主键。
     */
    @Column(name = "workspace_projection_id", nullable = false, updatable = false)
    private Long workspaceProjectionId;

    /**
     * Cell 覆盖窗口起始时间（含）。
     */
    @Column(name = "window_start", nullable = false, updatable = false)
    private OffsetDateTime windowStart;

    /**
     * Cell 覆盖窗口结束时间（含）。
     */
    @Column(name = "window_end", nullable = false, updatable = false)
    private OffsetDateTime windowEnd;

    /**
     * Cell 关闭原因：count_limit / window_limit / token_limit / stream_end。
     */
    @Column(name = "close_reason", nullable = false, length = 30, updatable = false)
    private String closeReason;

    /**
     * 本 Cell 包含的反馈事件数量。
     */
    @Column(name = "event_count", nullable = false, updatable = false)
    private int eventCount;

    /**
     * 本 Cell 的预估 token 数（本期为字符估算，不调用模型）。
     */
    @Column(name = "estimated_tokens", nullable = false, updatable = false)
    private int estimatedTokens;

    /**
     * 记录首次写入时刻。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * JPA 反射构造器；业务代码使用 {@link #of} 工厂方法。
     */
    protected DataCell() {
    }

    /**
     * 创建一个 Cell；字段来自 DataCellPlan。
     *
     * @param workspaceId         一级租户隔离键
     * @param workspaceProjectionId 所属投影内部主键
     * @param windowStart         窗口起始时间
     * @param windowEnd           窗口结束时间
     * @param closeReason         关闭原因
     * @param eventCount          事件数量
     * @param estimatedTokens     预估 token 数
     * @return 新建的 Data Cell
     */
    public static DataCell of(
            Long workspaceId, Long workspaceProjectionId,
            OffsetDateTime windowStart, OffsetDateTime windowEnd,
            String closeReason, int eventCount, int estimatedTokens) {
        DataCell cell = new DataCell();
        OffsetDateTime now = OffsetDateTime.now();
        cell.workspaceId = workspaceId;
        cell.workspaceProjectionId = workspaceProjectionId;
        cell.windowStart = windowStart;
        cell.windowEnd = windowEnd;
        cell.closeReason = closeReason;
        cell.eventCount = eventCount;
        cell.estimatedTokens = estimatedTokens;
        cell.createdAt = now;
        return cell;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getWorkspaceProjectionId() {
        return workspaceProjectionId;
    }

    public OffsetDateTime getWindowStart() {
        return windowStart;
    }

    public OffsetDateTime getWindowEnd() {
        return windowEnd;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public int getEventCount() {
        return eventCount;
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
