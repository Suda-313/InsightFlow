# 看板与报告 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** 看板摘要 API + 主题 API + 报告创建/查询 API。

**Architecture:** DashboardService 聚合查询已有事实表；ReportController 创建 AsyncTask → AnalysisReportTaskRunner 异步执行 → 调用 ReportAgent.generate()。

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, JUnit 5 + Mockito。

## Global Constraints

- 不修改 V1–V8 迁移
- 所有 API 按 workspace_id 隔离
- 不做 LLM 真实调用
- 不提交不推送

---

### Task 1: DashboardService + DashboardController

**Files:**
- Create: `src/main/java/com/insightflow/service/DashboardService.java`
- Create: `src/main/java/com/insightflow/controller/DashboardController.java`
- Test: `DashboardServiceTest.java`, `DashboardControllerTest.java`

**DashboardService 查询逻辑：**
- 注入 IssueMetricBucketRepository, IssueBaselineProfileRepository, AlertRepository, DataCellRepository, IssueCatalogRepository
- `getDashboard(workspaceId)`: 查最近 7 天 bucket → topIssues, 最近 alert, 基线状态, 数据覆盖
- `getIssues(workspaceId)`: 查 issue_catalog + 关联最近指标
- `getIssueDetail(workspaceId, canonicalKey)`: 单主题趋势 + 告警列表

**DashboardController：**
- 路径 `/api/v1/workspaces/{workspaceId}/dashboard`, `/issues`, `/issues/{canonicalKey}`
- 注入 WorkspaceService（校验 workspace 存在）+ DashboardService

- [ ] **Step 1: 写 DashboardService（聚合查询）**
- [ ] **Step 2: 写 DashboardController（REST 端点）**
- [ ] **Step 3: 写测试**
- [ ] **Step 4: 验证 + 暂存**

---

### Task 2: ReportCommandService + ReportController

**Files:**
- Create: `src/main/java/com/insightflow/service/ReportCommandService.java`
- Create: `src/main/java/com/insightflow/controller/ReportController.java`
- Create: `src/main/java/com/insightflow/task/AnalysisReportTaskRunner.java`
- Create: `src/main/java/com/insightflow/task/AnalysisReportCompletionService.java`

**ReportCommandService：**
- 注入 AnalysisReportRepository, AsyncTaskRepository
- `createReport(workspaceId, fileIds, timeRange, idempotencyKey)` → 创建 analysis_report + AsyncTask(type="analysis_report")

**AnalysisReportTaskRunner：**
- 参考 WorkspaceProjectionTaskRunner 的租约模式
- 执行时调用 ReportAgent.generate() 生成报告
- 写入 analysis_report.report_json

**ReportController：**
- POST `/api/v1/workspaces/{workspaceId}/analysis-reports` → 202 Accepted
- GET `/api/v1/workspaces/{workspaceId}/analysis-reports/{reportId}` → 报告状态+内容

- [ ] **Step 1: 写 ReportCommandService + ReportController**
- [ ] **Step 2: 写 AnalysisReportTaskRunner**
- [ ] **Step 3: 写测试**
- [ ] **Step 4: 验证 + 暂存**

---

### Task 3: 全量验证

- [ ] `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd test`
- [ ] `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd package -DskipTests`