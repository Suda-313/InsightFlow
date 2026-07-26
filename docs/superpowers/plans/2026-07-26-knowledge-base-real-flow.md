# 知识库与真实评论数据闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将真实 CSV 评论导入“超自然行动组”工作区，发布四类知识文档，运行实际 Agent/RAG 链路并记录评测结果。

**Architecture:** CSV 只经过既有导入、投影和分析管线，知识 Markdown 只经过既有“上传—待审核—发布—嵌入”管线。运行结果由既有 API 和数据库历史记录提供，项目开发日志只写入可复核的运行事实及阻断问题。

**Tech Stack:** Java 17、Spring Boot 3.5、PostgreSQL 16、Flyway、MinIO、Spring AI、DashScope embedding、Vue。

## Global Constraints

- 不新增爬虫、检索类型、向量库、评测框架或写操作 Agent。
- 原始评论 CSV、账号标识、密钥和模型原始思维链不得写入仓库或开发日志。
- 只有 `PUBLISHED` 知识版本参与 RAG；组织通用文档与工作区专属文档必须按既有范围上传。
- 若出现缺陷，只修复阻断本闭环的根因，并补最小回归测试。
- 未经用户再次授权，不执行 Git 提交或推送。

---

### Task 1: 核对输入数据与运行依赖

**Files:**
- Read: `output/taptap-review-2026-06-28-to-2026-07-11.csv`
- Read: `output/taptap-review-2026-07-12-to-2026-07-25.csv`
- Read: `src/main/java/com/insightflow/dto/importing/ImportMapping.java`
- Read: `src/main/java/com/insightflow/controller/FileImportController.java`
- Read: `src/main/resources/application*.yml`

**Consumes:** 两份 TapTap 评论 CSV 与既有导入映射契约。

**Produces:** 字段映射、行数、时间范围、重复/空值统计及服务健康检查结果。

- [ ] **Step 1: 读取 CSV 表头、编码与统计信息**

运行 PowerShell/Python 只读脚本，输出每个 CSV 的列名、行数、最早/最晚时间、关键字段空值和 `external_ref` 重复数；不得输出完整评论正文或作者信息。

- [ ] **Step 2: 对照导入映射契约**

确认当前已清洗 CSV 使用同名映射：`feedback_text -> feedback_text`、`occurred_at -> occurred_at`、`source -> source`、`external_ref -> external_ref`；`rating`、`platform` 和 `source_url` 作为可选 `dimensions`。

- [ ] **Step 3: 检查服务与依赖健康度**

运行 `docker compose ps`、`GET /actuator/health`，并确认 DashScope Chat/Embedding、MinIO、PostgreSQL 配置没有在命令输出中泄露密钥。

### Task 2: 编写并发布四类知识文档

**Files:**
- Create: `docs/knowledge-sources/超自然行动组-1.4-版本更新说明.md`
- Create: `docs/knowledge-sources/超自然行动组-已知问题与处置指引.md`
- Create: `docs/knowledge-sources/超自然行动组-玩家反馈响应-SOP.md`
- Create: `docs/knowledge-sources/游戏舆情分析与风险分级手册.md`
- Read: `src/main/java/com/insightflow/controller/KnowledgeDocumentController.java`

**Consumes:** Task 1 的数据时间范围、现有 `KnowledgeDocumentType` 与知识 API 生命周期。

**Produces:** 四份可切片 Markdown，及其上传/发布后返回的公开文档和版本 UUID。

- [ ] **Step 1: 编写工作区专属版本说明**

内容包含版本号、上线窗口、功能调整、已知影响和运营观察项；与 `KNOWN_ISSUE` 的问题编号相互引用，但不制造外部来源或真实官方归属。

- [ ] **Step 2: 编写工作区专属问题指引和客服 SOP**

问题指引至少覆盖稳定性、匹配/组队、结算/道具和举报反馈；SOP 明确分级、收集字段、升级条件、回复边界和闭环标准。

- [ ] **Step 3: 编写组织通用舆情手册**

明确主题归类、风险等级、证据门槛、告警复核、对外表达边界和复盘要求，避免把“高频”直接推断为根因。

- [ ] **Step 4: 上传并发布文档**

对前三份调用 `POST /api/v1/workspaces/{workspaceId}/knowledge/documents`，参数 `scope=WORKSPACE`；对手册使用 `scope=ORGANIZATION`。从创建响应取得 `documentId/versionId`，调用各自的 `/publish` 端点，并以 `GET /documents` 核对四个版本均为 `PUBLISHED`。

### Task 3: 导入 CSV 并验证确定性分析管线

**Files:**
- Read: `src/main/java/com/insightflow/controller/FileImportController.java`
- Read: `src/main/java/com/insightflow/service/FileImportService.java`
- Read: `src/main/java/com/insightflow/agent/AgentAnalysisScheduler.java`

**Consumes:** Task 1 映射和 Task 2 工作区 UUID。

**Produces:** 两批导入任务结果、投影/主题/指标/告警可见性与失败原因。

- [ ] **Step 1: 按已确认字段映射创建并执行两批导入**

使用既有导入 API 提交两份 CSV；每批轮询任务直到进入终态，记录导入成功、跳过、失败和脱敏计数，不记录原始评论。

- [ ] **Step 2: 等待既有异步分析结束**

从任务/API/日志确认投影任务完成后，查询 Dashboard 和 issue 列表，核对分析产物存在且范围属于该工作区。

- [ ] **Step 3: 处理阻断缺陷**

若导入、投影或读取 API 失败，先保存错误响应和最小复现；仅在根因确认后，先添加失败回归测试、再实现最小修复并运行相关测试。

### Task 4: 运行聊天和 RAG 评测并记录结果

**Files:**
- Read: `src/main/java/com/insightflow/controller/EvaluationController.java`
- Read: `src/main/java/com/insightflow/evaluation/GoldEvaluationMetrics.java`
- Read: `src/main/java/com/insightflow/evaluation/rag/RagEvaluationMetrics.java`
- Modify: `docs/project-development-log.md`

**Consumes:** 已发布知识文档、已完成的数据分析产物和现有评测 API。

**Produces:** 聊天证据样本、Gold 与 RAG 评测批次、项目开发记录。

- [ ] **Step 1: 运行代表性聊天问题**

分别发送一个版本问题、一个已知问题/SOP 问题、一个舆情数据问题和一个无依据问题。核对知识问题含 `knowledge:` 证据、数据问题含指标/告警证据、无依据问题不虚构事实；记录 trace ID、耗时和 Token（若 API/日志提供）。

- [ ] **Step 2: 运行既有 Gold 评测**

调用 `POST /api/v1/workspaces/{workspaceId}/evaluations/gold`，保留批次 ID 与指标：事实覆盖率、禁止断言率、拒答准确率、成功率、P50/P95 耗时和 Token。

- [ ] **Step 3: 运行既有 RAG 评测**

调用 `POST /api/v1/workspaces/{workspaceId}/evaluations/rag`，保留批次 ID 与指标：检索召回率、引用正确率、无依据回答率和失败案例。若现有 Gold 评测没有独立“意图识别准确率”，在记录中明确该限制，不虚构指标。

- [ ] **Step 4: 写入开发记录并复核**

向 `docs/project-development-log.md` 增加一节，写清运行日期、CSV 文件名/时间范围、文档版本、批次 ID、指标值、已验证问题、修复（如有）及限制。检查日志中不含评论原文、作者、密钥或思维链。

- [ ] **Step 5: 完成范围匹配的验证**

运行改动相关的 Maven 测试；若进行了代码改动，运行 `./mvnw.cmd test`。最后重新查询知识文档、导入任务和两类评测历史，确认记录与实际服务状态一致。
