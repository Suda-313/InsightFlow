package com.insightflow.repository;

import com.insightflow.entity.IssueMetricBucket;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 日指标桶持久化端口；按 (workspace_id, issue_id, bucket_start) 唯一查找，
 * 支持 MetricBucketService 的 UPSERT 语义。
 */
public interface IssueMetricBucketRepository extends JpaRepository<IssueMetricBucket, Long> {

    /**
     * 按唯一约束查找已有桶；返回 Optional 供 UPSERT 判断。
     *
     * @param workspaceId 一级租户隔离键
     * @param issueId     主题内部主键
     * @param bucketStart 日桶起点
     * @return 可能为空的已有桶
     */
    Optional<IssueMetricBucket> findByWorkspaceIdAndIssueIdAndBucketStart(
            Long workspaceId, Long issueId, OffsetDateTime bucketStart);

    /**
     * 按投影与工作区查询该次投影写入的所有日指标桶。
     *
     * @param workspaceProjectionId 投影内部主键
     * @param workspaceId           一级租户隔离键
     * @return 该次投影写入的日指标桶列表
     */
    List<IssueMetricBucket> findByWorkspaceProjectionIdAndWorkspaceId(
            Long workspaceProjectionId, Long workspaceId);

    /**
     * 查询工作区内指定时间之后的所有日指标桶，供看板按 issue 聚合反馈数。
     *
     * @param workspaceId 一级租户隔离键
     * @param bucketStart 日桶起始时间（含）
     * @return 匹配日指标桶列表
     */
    List<IssueMetricBucket> findByWorkspaceIdAndBucketStartGreaterThanEqual(
            Long workspaceId, OffsetDateTime bucketStart);
}