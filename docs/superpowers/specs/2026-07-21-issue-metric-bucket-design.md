# 日指标聚合（Issue Metric Bucket）设计

> 状态：待用户复核
> 日期：2026-07-21
> 分支：feature/data-cell-rule-issue-merging
> 前置文档：`docs/superpowers/specs/2026-07-20-data-cell-rule-issue-merging-design.md`

## 1. 目标与边界

在投影事务内，将 Data Cell 的分类结果按**日粒度**聚合为 `issue_metric_bucket` 行，为后续 EWMA 基线、z-score 告警和看板查询提供确定性事实来源。

**本期实现：**

1. `EventInput` 增加 `sourceKind` 字段，`ProjectionSourceLoader` 顺手填入；
2. `MetricBucketService` 在投影事务内按 `(issue_id, 日期)` 聚合 + UPSERT；
3. `dimension_summary_json` 统计 `source_kind` 分布（如 `{"工单": 15, "评价": 8}`）；
4. 插入在 `factWriter.write()` 之后、`recordSourceWindow()` 之前，同一 `REQUIRES_NEW` 事务。

**本期不实现：**

- `issue_baseline_profile` EWMA 基线
- `alert` z-score / 冷却期
- `rebuild_required` 晚到数据标记
- 按需分析报告、看板查询 API

## 2. 数据流

```text
WorkspaceProjectionExecutionService.execute()  [REQUIRES_NEW 单事务]
  ├─ ProjectionSourceLoader.load() → List<EventInput>（含 sourceKind）
  ├─ RuleFirstIssueClassifier.classify() → Map<eventId, List<Classification>>
  ├─ DataCellBuilder.split() → List<DataCellPlan>
  ├─ ProjectionFactWriter.write()  ← 已有：写 data_cell/cell_issue/link
  ├─ MetricBucketService.write()   ← 新增：日指标聚合 UPSERT
  └─ projection.recordSourceWindow()
```

## 3. EventInput 扩展

```java
// 之前
public record EventInput(Long id, OffsetDateTime occurredAt, String normalizedText) {}

// 之后
public record EventInput(Long id, OffsetDateTime occurredAt, String sourceKind, String normalizedText) {}
```

`ProjectionSourceLoader.load()` 在构造 `EventInput` 时填入 `FeedbackEvent.getSourceKind()`：
```java
inputs.add(new EventInput(event.getId(), event.getOccurredAt(),
        event.getSourceKind(),                    // 新增
        normalizer.normalize(event.getSanitizedText())));
```

## 4. MetricBucketService 算法

### 4.1 聚合逻辑

```
输入: events (List<EventInput>), classificationsByEventId (Map<Long, List<Classification>>)
输出: issue_metric_bucket 行（每 (issue_id, canonical_key, 日期) 一行）

1. 遍历 events:
   a. 取该事件的 classifications（0..2 条）
   b. 对每条 classification:
      - 按 issue_id + occurredAt 截断到日（LocalDate）分组
      - 组内累计 feedback_count += 1
      - 组内按 source_kind 分布计数（如 {"工单": 5, "评价": 3}）

2. 对每个分组执行 UPSERT:
   a. 查询是否已有 (workspace_id, issue_id, bucket_start) 的行
   b. 有 → UPDATE: feedback_count += 新增计数, 合并 dimension_summary_json
   c. 无 → INSERT 新行（feedback_count = 新增计数）

3. 全部在同一事务内，任一步失败整体回滚
```

### 4.2 日截断规则

`occurred_at` 按**事件本地日期**截断到 `00:00:00`，即 `OffsetDateTime` 的 `truncatedTo(DAYS)` 后再调整到 `bucket_start` 对应的时区同日零点。

简化处理：对所有 `occurred_at`，`bucket_start = occurred_at.toLocalDate().atStartOfDay(zone)` 转为 `OffsetDateTime`。时区固定 UTC（`ZoneOffset.UTC`），保证跨时区数据的一致性。

### 4.3 dimension_summary_json 合并

```json
// 首次写入
{"工单源": 12, "评价源": 5}

// 同 bucket 第二次投影（UPSERT 合并后）
{"工单源": 20, "评价源": 11}
```

合并方式：读取已有 JSON → 反序列化为 `Map<String, Integer>` → 逐 key 累加 → 序列化回 JSON。

## 5. 实体与仓储

### 5.1 IssueMetricBucket 实体

```java
@Entity
@Table(name = "issue_metric_bucket")
public class IssueMetricBucket {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;          // 租户隔离
    private Long issueId;              // → issue_catalog.id
    private OffsetDateTime bucketStart; // 日桶起点（UTC 00:00）
    private int feedbackCount;         // 当日反馈数
    private String dimensionSummaryJson; // JSONB: {"工单":5, "评价":3}
    private Long workspaceProjectionId; // 最后一次更新此桶的投影
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // 工厂方法: of(workspaceId, issueId, canonicalKey, bucketStart, ...)
    // 更新方法: addFeedbackCount(int delta, Map<String,Integer> dimensionDelta)
}
```

### 5.2 IssueMetricBucketRepository

```java
public interface IssueMetricBucketRepository extends JpaRepository<IssueMetricBucket, Long> {
    Optional<IssueMetricBucket> findByWorkspaceIdAndIssueIdAndBucketStart(
            Long workspaceId, Long issueId, OffsetDateTime bucketStart);
}
```

## 6. 事务与幂等

- **事务**：`MetricBucketService.write()` 在 `WorkspaceProjectionExecutionService.execute()` 的同一 `REQUIRES_NEW` 事务内调用，不另开事务。
- **幂等**：UPSERT 语义保证同一次投影重试时不会重复累计——`findByWorkspaceIdAndIssueIdAndBucketStart` 查到已有行则合并，查不到则新增。
- **唯一约束**：V6 已定义 `uq_issue_metric_bucket (workspace_id, issue_id, bucket_start)`，防并发写入同一桶。

## 7. 错误处理

| 失败场景 | 处理 |
|----------|------|
| 事件无分类（unclassified） | 不计入任何 bucket，不影响其他 bucket |
| JSON 反序列化失败 | 事务回滚，`PROJECTION_METRIC_WRITE_FAILED` |
| DB 唯一约束冲突 | 事务回滚，租约重试走 UPSERT 幂等路径 |
| 空事件列表 | `MetricBucketService.write()` 直接返回，不写任何行 |

## 8. 测试策略

| 测试类 | 覆盖 |
|--------|------|
| `MetricBucketServiceTest` | 单日聚合、跨日分桶、source_kind 分布、UPSERT 合并已有桶、空输入、unclassified 不计入 |
| 更新 `ProjectionSourceLoaderTest` | 验证 sourceKind 字段被正确填入 |
| 更新 `WorkspaceProjectionExecutionServiceTest` | 验证 metricBucketService.write() 被调用 |
| 更新 `DataCellBuilderTest` | EventInput 新构造器兼容 |

全部纯 Mockito 单元测试，不依赖真实 PG。

## 9. 新增/修改文件清单

| 操作 | 文件 |
|------|------|
| 新增 | `entity/IssueMetricBucket.java` |
| 新增 | `repository/IssueMetricBucketRepository.java` |
| 新增 | `service/analysis/MetricBucketService.java` |
| 新增 | `test/.../MetricBucketServiceTest.java` |
| 修改 | `service/analysis/EventInput.java` — 加 sourceKind |
| 修改 | `service/analysis/ProjectionSourceLoader.java` — 填入 sourceKind |
| 修改 | `service/analysis/WorkspaceProjectionExecutionService.java` — 插入 metricBucketService.write() |
| 修改 | 相关测试类 — 适配 EventInput 新字段 |

## 10. 非目标

- 不创建 `issue_baseline_profile` 实体/仓储/服务
- 不创建 `alert` 实体/仓储/服务
- 不实现 EWMA、z-score、冷却期
- 不修改 V1-V7 迁移
- 不修改 `dimension_json` 中除 source_kind 外的其他维度
- `unclassified` 不产生 bucket 行（只在 `data_cell.event_count` 中计入总数）