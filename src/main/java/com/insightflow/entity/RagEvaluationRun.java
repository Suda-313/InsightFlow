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
 * 一次 RAG 专项评测的不可变历史批次。
 *
 * <p>内部 {@code id} 只用于数据库关联，外部只使用 UUIDv7 {@code publicId}；
 * {@code workspaceId} 强制将指标、案例计数和未来的比较限制在同一工作区。</p>
 */
@Entity
@Table(name = "rag_evaluation_run")
public class RagEvaluationRun {

    /** 数据库内部主键，禁止通过 API、评测页面或模型上下文暴露。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外稳定批次标识，支持页面安全选择和引用，不暴露连续行号。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 评测归属的内部 Workspace 键，所有历史读取都必须同时匹配它。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 由可见已发布文档 UUID 生成的题集版本，知识变动后不能与旧批次混比。 */
    @Column(name = "dataset_version", nullable = false, length = 100, updatable = false)
    private String datasetVersion;

    /** 与线上聊天共用的提示词护栏版本，是解释质量差异的核心维度。 */
    @Column(name = "prompt_version", nullable = false, length = 100, updatable = false)
    private String promptVersion;

    /** 实际执行的模型名称，只由服务端配置提供，客户端不可伪造。 */
    @Column(name = "model_name", nullable = false, length = 120, updatable = false)
    private String modelName;

    /** 固定受控检索实现版本，便于区分 RRF 或护栏调整带来的指标变化。 */
    @Column(name = "retrieval_version", nullable = false, length = 100, updatable = false)
    private String retrievalVersion;

    /** 三项聚合指标 JSON；只保存数值，不保存企业原文或模型回答。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String metricsJson;

    /** 逐题脱敏计数 JSON；用于定位回归但不形成新的知识库副本。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "case_results_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String caseResultsJson;

    /** 批次完成并落库的时间，不允许后续更新篡改历史排序。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 仅供 JPA 映射创建；业务代码必须调用工厂方法保证字段齐全。 */
    protected RagEvaluationRun() {
    }

    /**
     * 从已经计算完成且序列化成功的评测结果创建历史快照。
     *
     * <p>该工厂不接触模型、文档或对象存储，确保实体职责仅是存储不可变审计数据。</p>
     */
    public static RagEvaluationRun create(
            Long workspaceId, String datasetVersion, String promptVersion, String modelName,
            String retrievalVersion, String metricsJson, String caseResultsJson) {
        RagEvaluationRun run = new RagEvaluationRun();
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

    /** 返回内部主键，仅供同进程持久化层使用。 */
    public Long getId() { return id; }
    /** 返回对外安全的批次 UUID。 */
    public UUID getPublicId() { return publicId; }
    /** 返回当前实体的 Workspace 隔离键。 */
    public Long getWorkspaceId() { return workspaceId; }
    /** 返回不可变数据集版本。 */
    public String getDatasetVersion() { return datasetVersion; }
    /** 返回提示词版本。 */
    public String getPromptVersion() { return promptVersion; }
    /** 返回模型名称。 */
    public String getModelName() { return modelName; }
    /** 返回受控检索版本。 */
    public String getRetrievalVersion() { return retrievalVersion; }
    /** 返回脱敏指标 JSON。 */
    public String getMetricsJson() { return metricsJson; }
    /** 返回脱敏逐题结果 JSON。 */
    public String getCaseResultsJson() { return caseResultsJson; }
    /** 返回历史创建时间。 */
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
