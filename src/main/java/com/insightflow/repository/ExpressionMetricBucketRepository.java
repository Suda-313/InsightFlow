package com.insightflow.repository;

import com.insightflow.entity.ExpressionMetricBucket;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * L2 日指标桶持久化端口；按 (workspace_id, primary_expression, bucket_start) 唯一查找，
 * 支持 ExpressionMetricBucketService 的 UPSERT 语义，与 IssueMetricBucketRepository 同构。
 */
public interface ExpressionMetricBucketRepository extends JpaRepository<ExpressionMetricBucket, Long> {

    /** 按唯一约束查找已有桶；返回 Optional 供 UPSERT 判断。 */
    Optional<ExpressionMetricBucket> findByWorkspaceIdAndPrimaryExpressionAndBucketStart(
            Long workspaceId, String primaryExpression, OffsetDateTime bucketStart);

    /** 按投影与工作区查询该次投影写入的所有 L2 日指标桶。 */
    List<ExpressionMetricBucket> findByWorkspaceProjectionIdAndWorkspaceId(
            Long workspaceProjectionId, Long workspaceId);

    /** 查询工作区内指定时间之后的所有 L2 日指标桶，供 Dashboard 首屏分布与趋势聚合。 */
    List<ExpressionMetricBucket> findByWorkspaceIdAndBucketStartGreaterThanEqual(
            Long workspaceId, OffsetDateTime bucketStart);
}
