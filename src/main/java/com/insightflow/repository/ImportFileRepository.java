package com.insightflow.repository;

import com.insightflow.entity.ImportFile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 文件元数据持久化端口。
 *
 * <p>所有公开 UUID 查询都连带 Workspace 内部键，避免单靠可猜测或泄露的 URL 参数跨域读取。</p>
 */
public interface ImportFileRepository extends JpaRepository<ImportFile, Long> {

    /**
     * 在目标 Workspace 内按公开 UUID 查文件，未命中统一转换为资源不存在。
     */
    Optional<ImportFile> findByWorkspaceIdAndPublicId(Long workspaceId, UUID publicId);

    /**
     * 映射保存和任务启动共享同一把行锁，防止两个请求将文件状态相互覆盖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select file from ImportFile file where file.workspaceId = :workspaceId and file.publicId = :publicId")
    Optional<ImportFile> findByWorkspaceIdAndPublicIdForUpdate(
            @Param("workspaceId") Long workspaceId, @Param("publicId") UUID publicId);

    /**
     * Worker 在完成任务时用内部任务已绑定的 workspace 二次限定文件，避免跨租户更新状态。
     */
    Optional<ImportFile> findByIdAndWorkspaceId(Long id, Long workspaceId);

    /**
     * 自动投影冻结来源文件时使用内部键加 Workspace 行锁，避免重试任务和人工状态操作并发覆盖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select file from ImportFile file where file.id = :fileId and file.workspaceId = :workspaceId")
    Optional<ImportFile> findByIdAndWorkspaceIdForUpdate(
            @Param("fileId") Long fileId, @Param("workspaceId") Long workspaceId);

    /**
     * 扫描投影 pending 且导入成功的文件，供调度器创建投影任务。
     */
    List<ImportFile> findByProjectionStatusAndStatus(String projectionStatus, String status);

    /** 导入页恢复最近一批文件状态时使用；必须按 Workspace 限定，避免跨租户读取。 */
    Optional<ImportFile> findFirstByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);
}
