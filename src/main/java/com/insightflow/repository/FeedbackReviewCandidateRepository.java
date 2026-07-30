package com.insightflow.repository;

import com.insightflow.entity.FeedbackReviewCandidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 复核候选只允许在已验证的 Workspace 范围内读取和改变状态。 */
public interface FeedbackReviewCandidateRepository extends JpaRepository<FeedbackReviewCandidate, Long> {

    /** 数据页默认只展示待处理候选，避免终态堆满运营操作区。 */
    List<FeedbackReviewCandidate> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(Long workspaceId, String status);

    /** 任何单条候选操作必须同时匹配 Workspace 内部键与对外 UUID。 */
    Optional<FeedbackReviewCandidate> findByWorkspaceIdAndPublicId(Long workspaceId, UUID publicId);

    /** 投影重试时用唯一业务键查找，保持候选队列幂等。 */
    boolean existsByWorkspaceProjectionIdAndFeedbackEventIdAndReasonCode(Long workspaceProjectionId,
                                                                           Long feedbackEventId,
                                                                           String reasonCode);

    /** Dashboard 首屏"L1 待复核"次要 KPI；只统计待处理，不含 confirmed/ignored 终态。 */
    long countByWorkspaceIdAndStatus(Long workspaceId, String status);
}
