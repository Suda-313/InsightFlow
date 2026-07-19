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

/**
 * 一次自动看板投影的可审计状态快照。
 *
 * <p>内部主键只用于关联 Data Cell、指标和 Alert；publicId 为未来 API 使用。每条记录均带
 * workspaceId，确保投影任务、来源文件与看板事实不能跨 Workspace 混读。</p>
 */
@Entity
@Table(name = "workspace_projection")
public class WorkspaceProjection {

    /** 数据库内部关联主键，不向 HTTP API 暴露。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUIDv7 是用户可见的投影追踪标识，不暴露递增内部编号。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 一级租户隔离键，所有投影读取与写入都必须同时带上它。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 承载租约、重试和终态的通用异步任务内部键。 */
    @Column(name = "async_task_id", nullable = false, unique = true, updatable = false)
    private Long asyncTaskId;

    /** queued / running / succeeded / rebuild_required / failed 的受控执行状态。 */
    @Column(nullable = false, length = 30)
    private String status;

    /** 分类规则版本被冻结到投影记录，避免后续规则变更无法解释历史趋势。 */
    @Column(name = "rule_version", nullable = false, length = 80, updatable = false)
    private String ruleVersion;

    /** 当前投影读取的反馈最早业务发生时间，便于数据覆盖范围审计。 */
    @Column(name = "source_window_start")
    private OffsetDateTime sourceWindowStart;

    /** 当前投影读取的反馈最晚业务发生时间，不能用任务创建时刻替代。 */
    @Column(name = "source_window_end")
    private OffsetDateTime sourceWindowEnd;

    /** 预警比较前冻结的基线截点，防止本批异常抬高自身阈值。 */
    @Column(name = "baseline_snapshot_at")
    private OffsetDateTime baselineSnapshotAt;

    /** 事实成功写入后的完成时间；失败或等待重建时保持 null。 */
    @Column(name = "projected_at")
    private OffsetDateTime projectedAt;

    /** 面向页面的受控失败码，不保存底层 SQL 或对象存储异常。 */
    @Column(name = "error_code", length = 80)
    private String errorCode;

    /** 无 PII、限长的失败摘要，详细堆栈只保留在日志。 */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** 命令受理时间，不代表指标已经进入看板。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 每次状态或审计字段改变后刷新，供任务状态查询稳定轮询。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 反射构造器；业务代码必须使用 queued 工厂方法。 */
    protected WorkspaceProjection() {
    }

    /** 创建尚未由 Worker 领取的自动投影记录。 */
    public static WorkspaceProjection queued(Long workspaceId, Long asyncTaskId, String ruleVersion) {
        WorkspaceProjection projection = new WorkspaceProjection();
        projection.publicId = UuidCreator.getTimeOrdered();
        projection.workspaceId = workspaceId;
        projection.asyncTaskId = asyncTaskId;
        projection.status = "queued";
        projection.ruleVersion = ruleVersion;
        projection.createdAt = OffsetDateTime.now();
        projection.updatedAt = projection.createdAt;
        return projection;
    }

    /** 标记 Worker 已开始执行；真正的事实写入仍由后续事务控制。 */
    public void markRunning() {
        this.status = "running";
        this.updatedAt = OffsetDateTime.now();
    }

    /** 在全部投影事实成功持久化后写入可解释的时间范围与基线截点。 */
    public void markSucceeded(OffsetDateTime start, OffsetDateTime end, OffsetDateTime baselineSnapshot) {
        this.status = "succeeded";
        this.sourceWindowStart = start;
        this.sourceWindowEnd = end;
        this.baselineSnapshotAt = baselineSnapshot;
        this.projectedAt = OffsetDateTime.now();
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = this.projectedAt;
    }

    /** 晚到数据不改写已有 EWMA，显式等待后续受控重建功能。 */
    public void markRebuildRequired() {
        this.status = "rebuild_required";
        this.updatedAt = OffsetDateTime.now();
    }

    /** 收敛为无 PII 的可展示失败，避免页面暴露数据库或模型实现细节。 */
    public void markFailed(String code, String message) {
        this.status = "failed";
        this.errorCode = code;
        this.errorMessage = message;
        this.updatedAt = OffsetDateTime.now();
    }

    /** 返回内部键，只供投影关联表和仓储使用。 */
    public Long getId() { return id; }

    /** 返回用户可见 UUIDv7。 */
    public UUID getPublicId() { return publicId; }

    /** 返回强制隔离的 Workspace 内部键。 */
    public Long getWorkspaceId() { return workspaceId; }

    /** 返回通用异步任务关联键。 */
    public Long getAsyncTaskId() { return asyncTaskId; }

    /** 返回当前投影状态。 */
    public String getStatus() { return status; }
}
