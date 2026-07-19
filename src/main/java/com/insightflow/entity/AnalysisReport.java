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
 * 用户按需创建的只读分析报告快照。
 *
 * <p>报告拥有独立 publicId 和异步任务，但绝不拥有指标、基线或 Alert 的写权限；它只能引用指定
 * Workspace 中已经完成投影的事实。</p>
 */
@Entity
@Table(name = "analysis_report")
public class AnalysisReport {

    /** 内部关联键，不向 API 暴露。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户可见的 UUIDv7 报告标识。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 所有报告读取都必须带上的 Workspace 隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 复用租约和重试能力的通用异步任务内部键。 */
    @Column(name = "async_task_id", nullable = false, unique = true, updatable = false)
    private Long asyncTaskId;

    /** queued / running / succeeded / failed 的报告执行状态。 */
    @Column(nullable = false, length = 30)
    private String status;

    /** 报告组装版本，便于后续新增字段后仍解释历史快照。 */
    @Column(name = "report_version", nullable = false, length = 80, updatable = false)
    private String reportVersion;

    /** 读取看板事实的截点，确保正在变化的趋势不会混入同一份报告。 */
    @Column(name = "source_snapshot_at", nullable = false, updatable = false)
    private OffsetDateTime sourceSnapshotAt;

    /** 已解析并冻结的文件与时间范围，不保存客户端原始自由文本。 */
    @Column(name = "scope_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String scopeJson;

    /** 只读结构化报告内容；初始 queued 状态保持 null。 */
    @Column(name = "report_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String reportJson;

    /** 受控失败码，禁止将内部异常类型输出给页面。 */
    @Column(name = "error_code", length = 80)
    private String errorCode;

    /** 无 PII、限长的失败说明。 */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** 受理时间，不等于报告可读取时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 状态或报告内容变更时刷新。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 反射构造器。 */
    protected AnalysisReport() {
    }

    /** 创建冻结范围但尚未生成内容的只读报告。 */
    public static AnalysisReport queued(
            Long workspaceId, Long asyncTaskId, String reportVersion, OffsetDateTime sourceSnapshotAt, String scopeJson) {
        AnalysisReport report = new AnalysisReport();
        report.publicId = UuidCreator.getTimeOrdered();
        report.workspaceId = workspaceId;
        report.asyncTaskId = asyncTaskId;
        report.status = "queued";
        report.reportVersion = reportVersion;
        report.sourceSnapshotAt = sourceSnapshotAt;
        report.scopeJson = scopeJson;
        report.createdAt = OffsetDateTime.now();
        report.updatedAt = report.createdAt;
        return report;
    }

    /** 报告 Worker 已开始读取既有看板事实。 */
    public void markRunning() {
        this.status = "running";
        this.updatedAt = OffsetDateTime.now();
    }

    /** 只保存组装结果，不触碰任何投影或预警表。 */
    public void markSucceeded(String generatedReportJson) {
        this.status = "succeeded";
        this.reportJson = generatedReportJson;
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = OffsetDateTime.now();
    }

    /** 收敛报告失败，同时保留之前的看板事实不变。 */
    public void markFailed(String code, String message) {
        this.status = "failed";
        this.errorCode = code;
        this.errorMessage = message;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 返回报告当前执行状态，查询层可据此区分尚未生成和可读取的结构化快照。
     */
    public String getStatus() {
        return status;
    }

    /**
     * 返回已经生成的只读报告 JSON；queued、running 或 failed 时该值可为 null。
     */
    public String getReportJson() {
        return reportJson;
    }
}
