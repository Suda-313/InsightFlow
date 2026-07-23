package com.insightflow.repository;

import com.insightflow.entity.AnalysisReport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 分析报告的持久化端口。
 */
public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    Optional<AnalysisReport> findByPublicIdAndWorkspaceId(UUID publicId, Long workspaceId);
    Optional<AnalysisReport> findByAsyncTaskIdAndWorkspaceId(Long asyncTaskId, Long workspaceId);

    /** 按工作区列出所有报告，按创建时间倒序。 */
    List<AnalysisReport> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);
}
