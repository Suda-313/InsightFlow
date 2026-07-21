package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 反馈到主题的可追溯关联；只引用已脱敏 feedback_event 与既有 issue_catalog。
 *
 * <p>唯一约束 (workspace_projection_id, feedback_event_id, issue_id) 防止重试重复累计。
 * 不存原文或归一文本，只存 issue_id 与 confidence。</p>
 */
@Entity
@Table(name = "feedback_issue_link")
public class FeedbackIssueLink {

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
     * 关联的已脱敏反馈事件内部主键。
     */
    @Column(name = "feedback_event_id", nullable = false, updatable = false)
    private Long feedbackEventId;

    /**
     * 关联的主题目录内部主键。
     */
    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    /**
     * 产生本次关联的投影内部主键，用于证据回溯。
     */
    @Column(name = "workspace_projection_id", nullable = false, updatable = false)
    private Long workspaceProjectionId;

    /**
     * 关联方式：rule / ambiguous；unclassified 不产生 link。
     */
    @Column(name = "assignment_method", nullable = false, length = 30, updatable = false)
    private String assignmentMethod;

    /**
     * 置信度；由 Classification 给出。
     */
    @Column(nullable = false)
    private double confidence;

    /**
     * 关联状态：active / excluded 等。
     */
    @Column(nullable = false, length = 30)
    private String status;

    /**
     * 记录首次写入时刻。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * JPA 反射构造器；业务代码使用 {@link #active} 工厂方法。
     */
    protected FeedbackIssueLink() {
    }

    /**
     * 创建一条活跃关联；assignment_method 与 confidence 来自 Classification。
     *
     * @param workspaceId          一级租户隔离键
     * @param feedbackEventId      反馈事件内部主键
     * @param issueId              主题目录内部主键
     * @param workspaceProjectionId 投影内部主键
     * @param assignmentMethod     关联方式
     * @param confidence           置信度
     * @return 新建的活跃反馈-主题关联
     */
    public static FeedbackIssueLink active(
            Long workspaceId, Long feedbackEventId, Long issueId, Long workspaceProjectionId,
            String assignmentMethod, double confidence) {
        FeedbackIssueLink link = new FeedbackIssueLink();
        OffsetDateTime now = OffsetDateTime.now();
        link.workspaceId = workspaceId;
        link.feedbackEventId = feedbackEventId;
        link.issueId = issueId;
        link.workspaceProjectionId = workspaceProjectionId;
        link.assignmentMethod = assignmentMethod;
        link.confidence = confidence;
        link.status = "active";
        link.createdAt = now;
        return link;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getFeedbackEventId() {
        return feedbackEventId;
    }

    public Long getIssueId() {
        return issueId;
    }

    public Long getWorkspaceProjectionId() {
        return workspaceProjectionId;
    }

    public String getAssignmentMethod() {
        return assignmentMethod;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
