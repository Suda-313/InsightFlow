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
 * 规则无法可靠收敛时创建的人工复核候选。
 *
 * <p>内部键只用于 Workspace 隔离和反馈事件关联，外部 API 只暴露 public_id；
 * 实体绝不复制评论正文，确认或忽略也绝不改写规则与既有统计事实。</p>
 */
@Entity
@Table(name = "feedback_review_candidate")
public class FeedbackReviewCandidate {

    /** 数据库内部主键，仅供关系与索引使用。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外候选标识，禁止使用可枚举的内部主键。 */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    /** 一等隔离键，所有查询与写操作必须同时验证。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 指向已脱敏存储的反馈事件，不在本表复制评论正文。 */
    @Column(name = "feedback_event_id", nullable = false, updatable = false)
    private Long feedbackEventId;

    /** 产生候选的投影，唯一约束保证重试不会重复堆积队列。 */
    @Column(name = "workspace_projection_id", nullable = false, updatable = false)
    private Long workspaceProjectionId;

    /** 受控原因：too_many_topics / ambiguous_topics / mixed_sentiment / unclassified。 */
    @Column(name = "reason_code", nullable = false, length = 40, updatable = false)
    private String reasonCode;

    /** 可为空的既有主题键建议；不能借此直接创建新主题。 */
    @Column(name = "suggested_issue_key", length = 100, updatable = false)
    private String suggestedIssueKey;

    /** 可为空的主题级情绪建议，仅存固定枚举。 */
    @Column(name = "suggested_sentiment", length = 20, updatable = false)
    private String suggestedSentiment;

    /** pending_review / confirmed / ignored，终态不允许反复覆盖。 */
    @Column(nullable = false, length = 30)
    private String status;

    /** 产生候选的时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 人工确认或忽略的时间，不记录操作者敏感信息到候选正文。 */
    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    /** JPA 专用构造器；业务代码只能通过 pending 创建。 */
    protected FeedbackReviewCandidate() {
    }

    /** 创建待复核候选，所有输入都来自服务端受控分类结果或内部事件键。 */
    public static FeedbackReviewCandidate pending(Long workspaceId, Long feedbackEventId, Long workspaceProjectionId, String reasonCode,
                                                  String suggestedIssueKey, String suggestedSentiment) {
        FeedbackReviewCandidate candidate = new FeedbackReviewCandidate();
        candidate.publicId = UuidCreator.getTimeOrdered();
        candidate.workspaceId = workspaceId;
        candidate.feedbackEventId = feedbackEventId;
        candidate.workspaceProjectionId = workspaceProjectionId;
        candidate.reasonCode = reasonCode;
        candidate.suggestedIssueKey = suggestedIssueKey;
        candidate.suggestedSentiment = suggestedSentiment;
        candidate.status = "pending_review";
        candidate.createdAt = OffsetDateTime.now();
        return candidate;
    }

    /** 仅待复核候选允许确认；确认只记录人工判断，不影响既有事实。 */
    public void confirm() {
        resolve("confirmed");
    }

    /** 仅待复核候选允许忽略；忽略同样不删除历史或规则。 */
    public void ignore() {
        resolve("ignored");
    }

    /** 统一保护终态不可回退，避免重复点击造成审计语义不一致。 */
    private void resolve(String targetStatus) {
        if (!"pending_review".equals(status)) {
            throw new IllegalStateException("复核候选当前不可处理");
        }
        status = targetStatus;
        resolvedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getFeedbackEventId() { return feedbackEventId; }
    public Long getWorkspaceProjectionId() { return workspaceProjectionId; }
    public String getReasonCode() { return reasonCode; }
    public String getSuggestedIssueKey() { return suggestedIssueKey; }
    public String getSuggestedSentiment() { return suggestedSentiment; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
}
