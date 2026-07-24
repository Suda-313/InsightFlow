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
 * 一次固定金标评测的可比较历史快照。
 *
 * <p>内部 {@code id} 仅用于数据库关联，外部读取和基线选择使用 UUIDv7 {@code publicId}；
 * {@code workspaceId} 是强制隔离键，任何历史读取与比较都必须携带它。</p>
 */
@Entity
@Table(name = "evaluation_run")
public class EvaluationRun {

    /** 数据库内部主键，绝不作为 API 返回值。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外公开的评测批次标识，支持客户端安全引用和基线选择。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 评测归属工作区的内部键，防止历史结果跨工作区读取或比较。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 固定题集版本，数据集变更后不能与旧基线直接比较。 */
    @Column(name = "dataset_version", nullable = false, length = 100, updatable = false)
    private String datasetVersion;

    /** 执行时的 Prompt 版本，是回归比较的首要维度。 */
    @Column(name = "prompt_version", nullable = false, length = 100, updatable = false)
    private String promptVersion;

    /** 执行评测的模型名称，用于成本与延迟差异解释。 */
    @Column(name = "model_name", nullable = false, length = 120, updatable = false)
    private String modelName;

    /** 检索策略版本；当前固定为 none，为后续 RAG 评测保留统一比较维度。 */
    @Column(name = "retrieval_version", nullable = false, length = 100, updatable = false)
    private String retrievalVersion;

    /** 汇总质量、延迟与 Token 指标的 JSON 快照，避免字段扩展时破坏历史记录。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String metricsJson;

    /** 30 条固定题的输出与规则得分 JSON 快照，用于定位具体题目提升或退化。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "case_results_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String caseResultsJson;

    /** 创建时间代表批次完成并写入历史的时刻，不允许后续更新篡改排序。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 仅供 JPA 反射，业务代码必须使用工厂方法建立完整快照。 */
    protected EvaluationRun() {
    }

    /**
     * 建立已完成评测的不可变快照；调用方负责先将结果序列化，避免实体依赖 JSON 工具或模型类型。
     */
    public static EvaluationRun create(
            Long workspaceId,
            String datasetVersion,
            String promptVersion,
            String modelName,
            String retrievalVersion,
            String metricsJson,
            String caseResultsJson) {
        EvaluationRun run = new EvaluationRun();
        run.publicId = UuidCreator.getTimeOrdered();
        run.workspaceId = workspaceId;
        run.datasetVersion = datasetVersion;
        run.promptVersion = promptVersion;
        run.modelName = modelName;
        run.retrievalVersion = retrievalVersion;
        run.metricsJson = metricsJson;
        run.caseResultsJson = caseResultsJson;
        run.createdAt = OffsetDateTime.now();
        return run;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getDatasetVersion() { return datasetVersion; }
    public String getPromptVersion() { return promptVersion; }
    public String getModelName() { return modelName; }
    public String getRetrievalVersion() { return retrievalVersion; }
    public String getMetricsJson() { return metricsJson; }
    public String getCaseResultsJson() { return caseResultsJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
