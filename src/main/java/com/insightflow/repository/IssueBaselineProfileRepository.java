package com.insightflow.repository;

import com.insightflow.entity.IssueBaselineProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * EWMA 基线持久化端口；按 (workspace_id, issue_id) 唯一查找，
 * 支持 ProjectionSaga 的 UPSERT 语义。
 *
 * <p>由于 {@code issue_baseline_profile} 表定义了
 * {@code UNIQUE (workspace_id, issue_id)} 约束，本接口提供
 * 按该复合键的精确查找，供判断是否已存在基线。</p>
 */
public interface IssueBaselineProfileRepository extends JpaRepository<IssueBaselineProfile, Long> {

    /**
     * 按唯一约束查找工作区下特定主题的基线。
     *
     * @param workspaceId 一级租户隔离键
     * @param issueId     主题目录内部主键
     * @return 包含基线的 Optional，不存在时返回 {@link Optional#empty()}
     */
    Optional<IssueBaselineProfile> findByWorkspaceIdAndIssueId(Long workspaceId, Long issueId);

    /**
     * 查找工作区下所有基线，用于看板基线状态统计。
     *
     * @param workspaceId 一级租户隔离键
     * @return 基线列表
     */
    List<IssueBaselineProfile> findByWorkspaceId(Long workspaceId);
}