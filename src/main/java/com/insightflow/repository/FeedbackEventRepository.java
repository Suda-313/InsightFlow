package com.insightflow.repository;

import com.insightflow.entity.FeedbackEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 脱敏反馈事实的持久化端口。
 *
 * <p>行级幂等由 Workspace、来源和外部引用哈希共同决定；内容哈希仅用于后续近似重复分析。</p>
 */
public interface FeedbackEventRepository extends JpaRepository<FeedbackEvent, Long> {

    /** 复核候选读取样本时必须同时约束内部事件键与 Workspace 隔离键。 */
    java.util.Optional<FeedbackEvent> findByIdAndWorkspaceId(Long id, Long workspaceId);

    /**
     * 在写入前查询完全相同的外部记录，避免重复导入造成重复反馈和虚假告警。
     */
    boolean existsByWorkspaceIdAndSourceIdAndExternalRefHash(
            Long workspaceId, Long sourceId, String externalRefHash);

    /**
     * 按投影来源文件对应的导入任务批量读取脱敏事件，按真实发生时间升序。
     * 调用方必须带 workspaceId 做二次隔离。
     */
    java.util.List<FeedbackEvent> findByWorkspaceIdAndIngestedTaskIdInOrderByOccurredAtAsc(
            Long workspaceId, java.util.Collection<Long> ingestedTaskIds);
}
