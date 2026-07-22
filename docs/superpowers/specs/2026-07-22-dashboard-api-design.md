# 看板与报告 API 设计

> 状态：待用户复核
> 日期：2026-07-22
> 分支：feature/data-cell-rule-issue-merging

## 1. 端点

### 1.1 看板摘要

```text
GET /api/v1/workspaces/{workspaceId}/dashboard
```

响应：
```json
{
  "dataCoverage": { "start": "2026-07-15T00:00:00Z", "end": "2026-07-21T00:00:00Z" },
  "totalEvents": 1250,
  "unclassifiedCount": 80,
  "topIssues": [
    { "canonicalKey": "login_failure", "name": "登录失败", "count": 45, "trend": "up" },
    { "canonicalKey": "payment_recharge", "name": "充值异常", "count": 30, "trend": "stable" }
  ],
  "recentAlerts": [
    { "issueKey": "login_failure", "zScore": 3.2, "currentCount": 45, "createdAt": "..." }
  ],
  "baselineStatus": { "totalIssues": 8, "buildingCount": 3, "activeCount": 5 },
  "lastProjection": { "status": "succeeded", "projectedAt": "..." }
}
```

### 1.2 主题列表

```text
GET /api/v1/workspaces/{workspaceId}/issues
```

### 1.3 主题详情

```text
GET /api/v1/workspaces/{workspaceId}/issues/{canonicalKey}
```

响应含日指标趋势（最近 7 天）、告警历史、基线状态。

### 1.4 创建报告

```text
POST /api/v1/workspaces/{workspaceId}/analysis-reports
Header: Idempotency-Key: <uuid>
Body: { "file_ids": ["uuid..."], "time_range": { "start": "...", "end": "..." } }
→ 202 Accepted { "taskId": "uuid..." }
```

### 1.5 查询报告

```text
GET /api/v1/workspaces/{workspaceId}/analysis-reports/{reportId}
→ { "status": "running|succeeded|failed", "reportJson": {...}, "reconciliation": {...} }
```

## 2. 服务层

### DashboardService
- `getDashboard(workspaceId)` → 聚合查询 issue_metric_bucket + alert + issue_baseline_profile + data_cell
- `getIssues(workspaceId)` → 查 issue_catalog + 关联指标
- `getIssueDetail(workspaceId, canonicalKey)` → 单主题趋势 + 告警 + 基线

### ReportCommandService
- `createReport(workspaceId, fileIds, timeRange, idempotencyKey)` → 创建 analysis_report + AsyncTask

### AnalysisReportTaskRunner
- 继承 AsyncTask 租约模式（参考 WorkspaceProjectionTaskRunner）
- 调用 ReportAgent.generate() 生成报告
- 写入 analysis_report.report_json

## 3. 新增文件

| 操作 | 文件 |
|------|------|
| 新增 | `controller/DashboardController.java` |
| 新增 | `controller/ReportController.java` |
| 新增 | `service/DashboardService.java` |
| 新增 | `service/ReportCommandService.java` |
| 新增 | `task/AnalysisReportTaskRunner.java` |
| 新增 | `task/AnalysisReportCompletionService.java` |
| 新增 | 测试 `DashboardControllerTest.java` |
| 新增 | 测试 `DashboardServiceTest.java` |

## 4. 非目标

- 不做 LLM 真实调用（ChatClient 已就绪）
- 不做前端页面
- 不修改已有迁移