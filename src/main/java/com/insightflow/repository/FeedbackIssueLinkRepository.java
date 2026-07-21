package com.insightflow.repository;

import com.insightflow.entity.FeedbackIssueLink;
import org.springframework.data.jpa.repository.JpaRepository;

/** 反馈-主题关联持久化端口；唯一约束防重试重复累计。 */
public interface FeedbackIssueLinkRepository extends JpaRepository<FeedbackIssueLink, Long> {
}
