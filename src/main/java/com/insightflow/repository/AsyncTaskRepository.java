package com.insightflow.repository;

import com.insightflow.entity.AsyncTask;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

/**
 * 异步任务持久化端口。
 *
 * <p>幂等查询必须带上 Workspace 和 task type，不能让不同命令或不同租户共享同一个键。</p>
 */
public interface AsyncTaskRepository extends JpaRepository<AsyncTask, Long> {

    /**
     * 查询同一导入命令的既有任务，供重复 POST 返回同一业务结果。
     */
    Optional<AsyncTask> findByWorkspaceIdAndTaskTypeAndIdempotencyKey(
            Long workspaceId, String taskType, String idempotencyKey);

    /**
     * 异步 Worker 按公开 UUID 读取任务；执行前仍会验证任务与文件 Workspace 一致。
     */
    Optional<AsyncTask> findByPublicId(UUID publicId);

    /**
     * 返回指定文件最近一次导入任务，文件结果页据此展示受控计数与错误摘要。
     */
    Optional<AsyncTask> findFirstByWorkspaceIdAndImportFileIdOrderByCreatedAtDesc(
            Long workspaceId, Long importFileId);

    /**
     * 用 PostgreSQL 的 SKIP LOCKED 选择一条可恢复任务；调用方必须在短事务内立即写入租约。
     */
    @Query(value = """
            select * from async_task
            where task_type = 'import'
              and (status = 'queued'
                   or (status = 'running'
                       and (lease_expires_at is null or lease_expires_at <= :now)))
            order by created_at asc
            for update skip locked
            limit 1
            """, nativeQuery = true)
    Optional<AsyncTask> findNextClaimableImportTask(@Param("now") OffsetDateTime now);

    /**
     * 投影和后续报告复用相同租约表，但通过 taskType 限制领取范围，不能误领 CSV 导入任务。
     */
    @Query(value = """
            select * from async_task
            where task_type = :taskType
              and (status = 'queued'
                   or (status = 'running'
                       and (lease_expires_at is null or lease_expires_at <= :now)))
            order by created_at asc
            for update skip locked
            limit 1
            """, nativeQuery = true)
    Optional<AsyncTask> findNextClaimableTaskByType(
            @Param("taskType") String taskType, @Param("now") OffsetDateTime now);
}
