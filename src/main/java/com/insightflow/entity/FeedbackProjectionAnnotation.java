package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 一条反馈在一次投影中的 L2（平台表达/意图）快照；每 event × 投影恰好 1 行。
 *
 * <p>与 {@link FeedbackIssueLink}（L1，0～2 行）是两张独立的分类事实表：L2 是全平台
 * 固定 5 类枚举、每条反馈唯一，L1 是 Workspace Topic Pack 私有议题、可 0～2 个并存。
 * 唯一约束 (workspace_projection_id, feedback_event_id) 防止投影重试重复累计，
 * 语义与 feedback_issue_link 的幂等约束完全对齐。</p>
 *
 * <p>topic_pack_id / topic_pack_version 只是"本次投影绑定了哪个 Pack"的追溯快照，
 * 不代表 primary_expression 由该 Pack 决定——L2 规则与 L1 Pack 是相互独立的两条规则线。</p>
 */
@Entity
@Table(name = "feedback_projection_annotation")
public class FeedbackProjectionAnnotation {

    /** 内部主键，仅供关联表使用，不对外暴露。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一级租户隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 产生本次标注的投影内部主键，用于证据回溯与幂等约束。 */
    @Column(name = "workspace_projection_id", nullable = false, updatable = false)
    private Long workspaceProjectionId;

    /** 关联的已脱敏反馈事件内部主键。 */
    @Column(name = "feedback_event_id", nullable = false, updatable = false)
    private Long feedbackEventId;

    /** L2 表达/意图主标签；固定枚举，不像 issue_id 那样引用动态目录表。 */
    @Column(name = "primary_expression", nullable = false, length = 30, updatable = false)
    private String primaryExpression;

    /** L2 置信度；唯一命中=1.0，mixed 并列=0.5，零命中兜底=1.0。 */
    @Column(name = "expression_confidence", nullable = false, updatable = false)
    private double expressionConfidence;

    /** v1 固定 "rule"；预留字段供未来 Pack 级 LLM 补位路径区分方法来源。 */
    @Column(name = "expression_method", nullable = false, length = 20, updatable = false)
    private String expressionMethod;

    /** 多意图同现且分差低于阈值时为 true；L2 不会因此进复核，仅用于统计观测。 */
    @Column(name = "mixed_expression", nullable = false, updatable = false)
    private boolean mixedExpression;

    /** 冻结本次投影使用的 L2 规则版本，如 platform:expression:v1。 */
    @Column(name = "expression_rule_version", nullable = false, length = 80, updatable = false)
    private String expressionRuleVersion;

    /** 冻结本次投影绑定的 Workspace Topic Pack 标识。 */
    @Column(name = "topic_pack_id", nullable = false, length = 80, updatable = false)
    private String topicPackId;

    /** 冻结本次投影绑定的 Workspace Topic Pack 版本。 */
    @Column(name = "topic_pack_version", nullable = false, length = 80, updatable = false)
    private String topicPackVersion;

    /**
     * Phase C：若对本事件尝试过 Pack LLM Topic Skill，冻结 Prompt 版本；未尝试则为 null。
     * 与 topic_pack_version 并列，便于解释 general 占比变化是否来自 Prompt 升版。
     */
    @Column(name = "topic_llm_prompt_version", length = 80, updatable = false)
    private String topicLlmPromptVersion;

    /**
     * Phase C：LLM 返回的议题置信度；低置信时 L1 仍写 topic_general，但保留观测值。
     * 规则路径或未调用 LLM 时为 null。
     */
    @Column(name = "topic_llm_confidence", updatable = false)
    private Double topicLlmConfidence;

    /** 记录首次写入时刻。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 反射构造器；业务代码使用 {@link #of} 工厂方法。 */
    protected FeedbackProjectionAnnotation() {
    }

    /**
     * 创建一条 L2 标注行；调用方（ProjectionAnnotationWriter）保证同一投影内每事件只调用一次。
     */
    public static FeedbackProjectionAnnotation of(
            Long workspaceId, Long workspaceProjectionId, Long feedbackEventId,
            String primaryExpression, double expressionConfidence, boolean mixedExpression,
            String expressionRuleVersion, String topicPackId, String topicPackVersion) {
        return of(workspaceId, workspaceProjectionId, feedbackEventId, primaryExpression, expressionConfidence,
                mixedExpression, expressionRuleVersion, topicPackId, topicPackVersion, null, null);
    }

    /**
     * 创建一条 L2 标注行；可选携带 Pack LLM Topic Skill 追溯字段。
     */
    public static FeedbackProjectionAnnotation of(
            Long workspaceId, Long workspaceProjectionId, Long feedbackEventId,
            String primaryExpression, double expressionConfidence, boolean mixedExpression,
            String expressionRuleVersion, String topicPackId, String topicPackVersion,
            String topicLlmPromptVersion, Double topicLlmConfidence) {
        FeedbackProjectionAnnotation annotation = new FeedbackProjectionAnnotation();
        annotation.workspaceId = workspaceId;
        annotation.workspaceProjectionId = workspaceProjectionId;
        annotation.feedbackEventId = feedbackEventId;
        annotation.primaryExpression = primaryExpression;
        annotation.expressionConfidence = expressionConfidence;
        annotation.expressionMethod = "rule";
        annotation.mixedExpression = mixedExpression;
        annotation.expressionRuleVersion = expressionRuleVersion;
        annotation.topicPackId = topicPackId;
        annotation.topicPackVersion = topicPackVersion;
        annotation.topicLlmPromptVersion = topicLlmPromptVersion;
        annotation.topicLlmConfidence = topicLlmConfidence;
        annotation.createdAt = OffsetDateTime.now();
        return annotation;
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

    public Long getFeedbackEventId() {
        return feedbackEventId;
    }

    public String getPrimaryExpression() {
        return primaryExpression;
    }

    public double getExpressionConfidence() {
        return expressionConfidence;
    }

    public String getExpressionMethod() {
        return expressionMethod;
    }

    public boolean isMixedExpression() {
        return mixedExpression;
    }

    public String getExpressionRuleVersion() {
        return expressionRuleVersion;
    }

    public String getTopicPackId() {
        return topicPackId;
    }

    public String getTopicPackVersion() {
        return topicPackVersion;
    }

    public String getTopicLlmPromptVersion() {
        return topicLlmPromptVersion;
    }

    public Double getTopicLlmConfidence() {
        return topicLlmConfidence;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
