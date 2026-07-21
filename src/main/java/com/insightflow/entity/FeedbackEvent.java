package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 可参与后续主题与异常分析的一条脱敏反馈事实。
 *
 * <p>此实体故意没有 public UUID：V1 不提供按任意反馈直接访问的 API。内部自增 id 仅用于
 * 后续证据关联，{@code workspaceId} 与 {@code sourceId} 共同约束外部引用去重范围。</p>
 */
@Entity
@Table(name = "feedback_event")
public class FeedbackEvent {

    /**
     * 内部关联主键，不对 API、模型上下文或 Trace 直接暴露。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 一级 Workspace 隔离字段，所有分析查询必须显式带上它。
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * 来源内部键，与 externalRefHash 联合构成行级幂等边界。
     */
    @Column(name = "source_id", nullable = false, updatable = false)
    private Long sourceId;

    /**
     * 外部记录标识的 SHA-256，不保存工单号、用户 ID 等真实引用。
     */
    @Column(name = "external_ref_hash", nullable = false, length = 64, updatable = false)
    private String externalRefHash;

    /**
     * 反馈真实发生时间，后续时间桶和异常检测以此而非导入时间为准。
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    /**
     * 来源分类来自映射列的受控文本，用于后续按来源维度的统计。
     */
    @Column(name = "source_kind", nullable = false, length = 64, updatable = false)
    private String sourceKind;

    /**
     * 已脱敏的用户反馈，是唯一可被 API、规则和模型使用的文本。
     */
    @Column(name = "sanitized_text", nullable = false, columnDefinition = "text", updatable = false)
    private String sanitizedText;

    /**
     * 基于脱敏文本的规范化版本，用于稳定内容哈希和规则归并。
     */
    @Column(name = "normalized_text", nullable = false, columnDefinition = "text", updatable = false)
    private String normalizedText;

    /**
     * 渠道、版本等可选维度 JSON，不作为 V1 的任意查询入口。
     */
    @Column(name = "dimension_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String dimensionJson;

    /**
     * 规范化、脱敏文本摘要，辅助重复内容观测而不替代 external reference 去重。
     */
    @Column(name = "content_hash", nullable = false, length = 64, updatable = false)
    private String contentHash;

    /**
     * 导入任务内部键，确保每条事实均可追溯到一次受控异步执行。
     */
    @Column(name = "ingested_task_id", nullable = false, updatable = false)
    private Long ingestedTaskId;

    /**
     * active / excluded / expired 等状态，V1 导入成功后固定写入 active。
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * 记录事实首次写入的时刻，不能用 id 推断业务发生顺序。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 为未来排除/失效状态预留的更新时间；初次写入时与 createdAt 相同。
     */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * JPA 反射构造器；业务代码用 {@link #active} 保证脱敏和哈希字段齐全。
     */
    protected FeedbackEvent() {
    }

    /**
     * 创建一条可分析的脱敏反馈；调用方必须先完成映射校验、PII 替换和时间解析。
     */
    public static FeedbackEvent active(
            Long workspaceId,
            Long sourceId,
            String externalRefHash,
            OffsetDateTime occurredAt,
            String sourceKind,
            String sanitizedText,
            String normalizedText,
            String dimensionJson,
            String contentHash,
            Long ingestedTaskId) {
        FeedbackEvent event = new FeedbackEvent();
        event.workspaceId = workspaceId;
        event.sourceId = sourceId;
        event.externalRefHash = externalRefHash;
        event.occurredAt = occurredAt;
        event.sourceKind = sourceKind;
        event.sanitizedText = sanitizedText;
        event.normalizedText = normalizedText;
        event.dimensionJson = dimensionJson;
        event.contentHash = contentHash;
        event.ingestedTaskId = ingestedTaskId;
        event.status = "active";
        event.createdAt = OffsetDateTime.now();
        event.updatedAt = event.createdAt;
        return event;
    }

    /**
     * 返回仅供同进程去重查询使用的外部引用摘要，绝不由 HTTP API 或模型上下文输出。
     */
    public String getExternalRefHash() {
        return externalRefHash;
    }

    /** 返回内部主键，仅供投影关联与 cell_issue 样本引用。 */
    public Long getId() { return id; }
    /** 返回一级隔离键，投影读取必须二次过滤。 */
    public Long getWorkspaceId() { return workspaceId; }
    /** 返回脱敏文本，是规则与未来模型唯一可用的文本。 */
    public String getSanitizedText() { return sanitizedText; }
    /** 返回反馈发生时间，决定时间窗与排序。 */
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    /** 返回来源分类，用于后续维度统计。 */
    public String getSourceKind() { return sourceKind; }
}
