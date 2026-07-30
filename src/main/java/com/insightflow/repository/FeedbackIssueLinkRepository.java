package com.insightflow.repository;

import com.insightflow.entity.FeedbackIssueLink;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 反馈-主题关联持久化端口；唯一约束防重试重复累计。 */
public interface FeedbackIssueLinkRepository extends JpaRepository<FeedbackIssueLink, Long> {

    /**
     * 按 Workspace + 事件 id 集合批量查询 L1 关联；供 Dashboard L2→L1 钻取按
     * feedback_projection_annotation 命中的事件集合反查其 L1 议题分布。
     *
     * @param workspaceId      一级租户隔离键
     * @param feedbackEventIds 反馈事件内部主键集合
     * @return 命中的 L1 关联行；调用方需按 (feedbackEventId, workspaceProjectionId) 与标注行对齐
     */
    List<FeedbackIssueLink> findByWorkspaceIdAndFeedbackEventIdIn(Long workspaceId, Collection<Long> feedbackEventIds);
}
