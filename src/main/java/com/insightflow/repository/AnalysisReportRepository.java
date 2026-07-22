package com.insightflow.repository;

import com.insightflow.entity.AnalysisReport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 分析报告的持久化端口。
 *
 * <p>所有查询必须同时带上 Workspace 内部键，避免仅凭公开 UUID 跨租户读取。</p>
 */
public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    /** 按公开 UUID 和 Workspace 读取报告详情。 */
    Optional<AnalysisReport> findByPublicIdAndWorkspaceId(UUID publicId, Long workspaceId);

    /** 按关联任务和 Workspace 读取报告，供 Worker 收敛终态。 */
    Optional<AnalysisReport> findByAsyncTaskIdAndWorkspaceId(Long asyncTaskId, Long workspaceId);
}
