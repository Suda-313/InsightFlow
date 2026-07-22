# EWMA 基线与告警设计

> 状态：待用户复核
> 日期：2026-07-21
> 分支：feature/data-cell-rule-issue-merging
> 前置文档：`docs/superpowers/specs/2026-07-21-issue-metric-bucket-design.md`
> 参考实现：`D:\ticket_automation_system-main\ticket_system\core\strategy\baseline.py`、`spike_detector.py`

## 1. 目标与边界

在投影事务内，基于 `issue_metric_bucket` 日指标更新 EWMA 基线，并检测突增告警。

**本期实现：**

1. `EwmaBaselineService` — 按日增量更新 EWMA + variance，二维象限分类（surge/escalating/chronic/longtail/normal）
2. `AlertDetector` — 动态阈值 + z-score 检测 + 冷却期去重
3. 配置参数全部对齐参考项目 `baseline.py` 和 `spike_detector.py`

**本期不实现：**

- 动态策略（per-issue 阈值调整、LLM 调参）
- 策略过期/恢复（TTL）
- 运营建议生成
- 飞书推送

## 2. 参数（对齐参考项目）

| 参数 | 值 | 来源 |
|------|-----|------|
| EWMA_ALPHA | 0.3 | `settings.toml [CHAOZIRAN_STRATEGY]` |
| MIN_HISTORY_DAYS | 3 | `baseline.py DEFAULT_MIN_HISTORY_DAYS` |
| SURGE_Z | 2.0 | `baseline.py DEFAULT_SURGE_Z` |
| SURGE_MIN | 5 | `baseline.py DEFAULT_SURGE_MIN` |
| CHRONIC_BASELINE | 5.0 | `baseline.py DEFAULT_CHRONIC_BASELINE` |
| LONGTAIL_MAX | 2 | `baseline.py DEFAULT_LONGTAIL_MAX` |
| ALERT_COOLDOWN_HOURS | 6 | `settings.toml ALERT_COOLDOWN_HOURS` |
| GLOBAL_ALERT_THRESHOLD | 10 | `settings.toml ALERT_THRESHOLD` |

## 3. 数据流

```text
WorkspaceProjectionExecutionService.execute()  [REQUIRES_NEW 单事务]
  ├─ factWriter.write()           — data_cell + cell_issue + link
  ├─ metricBucketService.write()  — issue_metric_bucket
  ├─ ewmaBaselineService.update() — issue_baseline_profile（新增）
  └─ alertDetector.detect()       — alert（新增）
```

## 4. EwmaBaselineService 算法

### 4.1 update_ewma（对齐 baseline.py）

```
输入: workspaceId, issueId, canonicalKey, bucketStart(日期), todayCount
参数: α=0.3, minHistoryDays=3

1. 查 issue_baseline_profile (workspace_id, issue_id)
2. 无 profile → 新建:
   baseline_ewma = todayCount
   baseline_variance = 0
   active_buckets = 1
   last_processed_bucket = bucketStart
   status = "baseline_building"（active_buckets < minHistoryDays）

3. 有 profile:
   a. 幂等: last_processed_bucket == bucketStart → 只更新 last_value，不更新 ewma
   b. old_ewma = profile.baseline_ewma
   c. new_ewma = α × todayCount + (1-α) × old_ewma
   d. new_variance = α × (todayCount - old_ewma)² + (1-α) × old_variance
   e. active_buckets = old_active_buckets + 1
   f. 更新 last_processed_bucket = bucketStart, last_value = todayCount
   g. status = active_buckets < minHistoryDays ? "baseline_building" : "active"
```

### 4.2 classify_issue（对齐 baseline.py）

```
z = (todayCount - baseline_ewma) / max(baseline_std, 1.0)

if active_buckets < minHistoryDays:
    if todayCount >= surgeMin(5):      return "surge"
    if todayCount <= longtailMax(2):   return "longtail"
    return "normal"

if ewma >= chronicBaseline(5.0) AND z < surgeZ(2.0):
    return "chronic"

if z >= surgeZ(2.0) AND todayCount >= surgeMin(5):
    if ewma >= chronicBaseline(5.0):   return "escalating"
    return "surge"

if todayCount <= longtailMax(2) AND ewma < chronicBaseline(5.0):
    return "longtail"

return "normal"
```

## 5. AlertDetector 算法

### 5.1 动态阈值（对齐 spike_detector.py）

```
effectiveThreshold = max(
    globalAlertThreshold(10),
    round(baseline_ewma + surgeZ × baseline_std)
)
```

### 5.2 检测流程

```
1. 对每个 issue 的当日 bucket:
   a. 查 baseline profile
   b. z_score = (todayCount - ewma) / max(std, 1.0)
   c. effectiveThreshold = max(10, round(ewma + 2.0 × std))
   d. if todayCount < effectiveThreshold: 跳过
   e. 查同 issue 最近 alert: 检查冷却期（6h）
   f. 在冷却期内: 跳过
   g. 创建 alert(status="active"):
      - z_score, baseline_ewma, baseline_stddev
      - effective_threshold, current_count
      - evidence_json: {bucket_start, issue_key, today_count, ewma, std, z_score}
```

## 6. 实体

### 6.1 IssueBaselineProfile（表已 V6 建好）

```sql
issue_baseline_profile:
  id, workspace_id, issue_id,
  active_buckets, baseline_ewma, baseline_variance,
  last_processed_bucket, last_value,
  status, classification,
  created_at, updated_at
```

### 6.2 Alert（表已 V6 建好）

```sql
alert:
  id, public_id, workspace_id, issue_id, workspace_projection_id,
  bucket_start, current_count,
  baseline_ewma, baseline_stddev, z_score,
  effective_threshold, status, evidence_json,
  created_at, updated_at
```

## 7. 事务与幂等

- 两个服务都在 `WorkspaceProjectionExecutionService.execute()` 的同一 `REQUIRES_NEW` 事务内
- `EwmaBaselineService`: `last_processed_bucket` 幂等守卫，同日不重复更新
- `AlertDetector`: 冷却期由 `created_at` 判定，不依赖额外状态表

## 8. 测试策略

| 测试类 | 覆盖 |
|--------|------|
| `EwmaBaselineServiceTest` | 新建 profile、同日幂等、增量更新、active_buckets 累积、分类（surge/escalating/chronic/longtail/normal） |
| `AlertDetectorTest` | 超阈值触发、低于阈值跳过、冷却期跳过、动态阈值计算 |

## 9. 新增/修改文件

| 操作 | 文件 |
|------|------|
| 新增 | `entity/IssueBaselineProfile.java` |
| 新增 | `entity/Alert.java` |
| 新增 | `repository/IssueBaselineProfileRepository.java` |
| 新增 | `repository/AlertRepository.java` |
| 新增 | `service/analysis/EwmaBaselineService.java` |
| 新增 | `service/analysis/AlertDetector.java` |
| 新增 | 测试 `EwmaBaselineServiceTest.java` |
| 新增 | 测试 `AlertDetectorTest.java` |
| 修改 | `application.yml` — 加 analysis 配置块 |
| 修改 | `config/AnalysisConfiguration.java` — 暴露配置值 |
| 修改 | `service/analysis/WorkspaceProjectionExecutionService.java` — 集成两个服务 |
| 修改 | 测试 `WorkspaceProjectionExecutionServiceTest.java` — 适配新构造器