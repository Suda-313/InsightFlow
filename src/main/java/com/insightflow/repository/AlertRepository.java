package com.insightflow.repository;

import com.insightflow.entity.Alert;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Alert 持久化端口；按 (workspace_id, issue_id) 查询最新预警，
 * 支持预警去重和状态检查。
 *
 * <p>预警创建后不可修改，系统通过 {@link #findTopByWorkspaceIdAndIssueIdOrderByCreatedAtDesc}
 * 获取最近一条预警，用于判断是否需要创建新预警（避免同一 issue 频繁重复预警）。</p>
 */
public interface AlertRepository extends JpaRepository<Alert, Long> {

    /**
     * 查找工作区下特定主题的最新一条预警（按创建时间降序）。
     *
     * @param workspaceId 一级租户隔离键
     * @param issueId     主题目录内部主键
     * @return 包含最新预警的 Optional，没有预警时返回 {@link Optional#empty()}
     */
    Optional<Alert> findTopByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(Long workspaceId, Long issueId);

    /**
     * 查找工作区下最近的 5 条预警，用于看板告警摘要。
     *
     * @param workspaceId 一级租户隔离键
     * @return 最近 5 条预警列表
     */
    List<Alert> findTop5ByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    /**
     * 查找工作区下特定主题的所有预警，按创建时间降序排列。
     *
     * @param workspaceId 一级租户隔离键
     * @param issueId     主题目录内部主键
     * @return 预警列表
     */
    List<Alert> findByWorkspaceIdAndIssueIdOrderByCreatedAtDesc(Long workspaceId, Long issueId);
}