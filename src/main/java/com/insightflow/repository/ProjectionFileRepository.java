package com.insightflow.repository;

import com.insightflow.entity.ProjectionFile;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 投影输入文件冻结记录的持久化端口。
 */
public interface ProjectionFileRepository extends JpaRepository<ProjectionFile, Long> {

    /** 返回同一 Workspace 投影所冻结的全部文件，供 Worker 批量推进投影状态。 */
    List<ProjectionFile> findByWorkspaceProjectionIdAndWorkspaceId(Long workspaceProjectionId, Long workspaceId);
}
