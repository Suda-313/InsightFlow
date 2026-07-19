# InsightFlow 开发交接手册

> 最后更新：2026-07-20
> 当前分支：`codex/csv-import-reliability`
> 远程仓库：`https://github.com/Suda-313/yuqiagent.git`

## 1. 给下一位 Codex 的任务说明

将下面这段完整发送给新的 Codex，并在它开始修改前要求它先阅读本文件、`AGENTS.md` 和 `docs/superpowers/specs/2026-07-20-workspace-analysis-report-and-alert-design.md`：

```text
请继续开发 D:\insightflow 的 InsightFlow 项目。先完整阅读 AGENTS.md、docs/HANDOFF.md、
docs/superpowers/specs/2026-07-20-workspace-analysis-report-and-alert-design.md 和
docs/superpowers/plans/2026-07-20-dashboard-projection-and-on-demand-report.md。

技术栈固定为 Java 17 + Spring Boot 3.5 + PostgreSQL 16 + Flyway + MinIO + Docker Compose；
保持模块化单体和 MVC 风格包结构，不改成 Python，不引入 Kafka、微服务、真实爬虫、多 Agent 或真实 LLM 调用。

当前已完成 CSV 导入和“导入成功 → 自动 projection 任务 → 文件 projected”的状态闭环。
下一阶段实现 Data Cell 与规则优先主题归并：只读取脱敏 FeedbackEvent，规则版本化；不接入 Qwen，
不计算 EWMA 或 Alert，先为后续指标计算提供可追溯的 feedback_issue_link、data_cell、cell_issue 事实。

每个新增业务、实体、任务和迁移模块的有效注释行数不得少于非空代码行数的 1/2；先写失败测试，
再写最小实现；不要提交或推送，除非用户明确要求。完成后运行 mvnw.cmd test 和 mvnw.cmd package。
```

## 2. 新电脑恢复步骤

1. 安装 JDK 17、Docker Desktop（启用 WSL 2）和 Git；Maven 使用项目自带的 `mvnw.cmd`，不必全局安装 Maven。
2. 克隆并切换当前分支：

   ```powershell
   git clone https://github.com/Suda-313/yuqiagent.git D:\insightflow
   Set-Location D:\insightflow
   git checkout codex/csv-import-reliability
   ```

3. 若 C 盘空间紧张，将 Maven 本地仓库放在 D 盘后再运行 Wrapper：

   ```powershell
   $env:MAVEN_USER_HOME = 'D:\maven-repository'
   $env:JAVA_HOME = 'D:\Develop\Java\jdk-17'
   ```

4. 从 `.env.example` 创建本地 `.env`；不要把密码、MinIO Access Key 或其他密钥提交到 Git。
5. 启动本地依赖并验证：

   ```powershell
   docker compose up -d
   docker compose ps
   .\mvnw.cmd test
   .\mvnw.cmd package
   .\mvnw.cmd spring-boot:run
   ```

6. 访问 `http://localhost:8080/actuator/health`。若 8080 被已有进程占用，可临时使用：

   ```powershell
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
   ```

## 3. 已完成能力

### CSV 导入

- Workspace 隔离；所有公开 API 使用 UUIDv7，内部关联使用自增 `BIGINT`；
- CSV 上传到 MinIO，PostgreSQL 只保存文件元数据与已脱敏 `feedback_event`；
- 表头预览、字段映射、PII 脱敏、外部引用哈希去重；
- `async_task` 通过 PostgreSQL 租约、`SKIP LOCKED` 和最大重试次数可靠执行导入任务；
- 导入结果可区分成功、重复、行级失败和任务级失败。

### 自动投影状态闭环

```text
ImportTask 成功提交
→ ImportTaskCompletionService 的 afterCommit 回调
→ WorkspaceProjectionCommandService 创建幂等 projection 命令
→ WorkspaceProjectionScheduler 领取租约
→ WorkspaceProjectionTaskRunner 执行
→ WorkspaceProjectionCompletionService 收敛终态
→ import_file.projection_status = projected
```

- 投影任务类型为 `projection`，与 `import` 共用 `async_task` 租约状态机，但拥有独立调度器和线程池；
- 幂等键格式为 `projection:file:{importFileId}:{ruleVersion}`；同一文件、同一规则版本不会重复创建任务；
- 文件导入状态和投影状态严格分开：`processed` 表示脱敏反馈已落库，`projected` 表示已进入后续看板计算入口；
- 当前投影只完成状态闭环，故 `WorkspaceProjection` 的时间窗、主题、指标、基线与 Alert 仍为空；这是刻意的分阶段实现，不是数据计算遗漏；
- 投影失败将文件标为 `projection_failed`，但不会回滚已经成功的 CSV 导入。

## 4. 当前目录与关键文件

```text
src/main/java/com/insightflow/
  controller/               # HTTP 边界
  service/                  # CSV 上传、映射和 Workspace 用例
  service/importing/        # CSV、PII、哈希等纯服务
  task/
    ImportTask*             # CSV 导入命令、租约、调度、Worker、完成服务
    WorkspaceProjection*    # 自动投影命令、租约、调度、Worker、完成服务
  entity/                   # JPA 实体与状态转换
  repository/               # Workspace-scoped Spring Data 查询
  storage/                  # MinIO 原始文件端口
  dto/                      # HTTP 与任务 payload
  config/                   # 异步线程池、MinIO 等配置
```

投影核心文件：

- `task/WorkspaceProjectionCommandService.java`
- `task/WorkspaceProjectionLeaseService.java`
- `task/WorkspaceProjectionScheduler.java`
- `task/WorkspaceProjectionTaskRunner.java`
- `task/WorkspaceProjectionCompletionService.java`
- `entity/WorkspaceProjection.java`
- `entity/ImportFile.java`
- `resources/db/migration/V6__add_dashboard_projection_schema.sql`

## 5. 数据库状态

Flyway 当前版本为 V6。不要修改 V1--V6；新增表或字段必须创建新的前向迁移。

V6 已预留以下后续数据结构：`workspace_projection`、`projection_file`、`issue_catalog`、`issue_alias`、`feedback_issue_link`、`data_cell`、`cell_issue`、`issue_metric_bucket`、`issue_baseline_profile`、`alert`、`analysis_report`、`analysis_report_file`。

## 6. 已验证结果

- `mvnw.cmd test`：16 个测试通过；
- `mvnw.cmd package`：成功；
- 本地 PostgreSQL 已成功执行 Flyway V6；
- 当前 JAR 在端口 8081 完成 Spring Boot 启动，新增 7 个 JPA Repository 被发现并装配；
- `git diff --check` 无空白错误。

## 7. 下一阶段：Data Cell 与规则优先主题归并

目标：将已投影的脱敏 `FeedbackEvent` 以确定性方式映射为可追溯主题事实，但暂不计算趋势、EWMA 或 Alert。

建议拆分：

1. 新增版本化 `config/analysis/issue-rules.toml`，定义 `canonical_key`、名称、优先级、正向和排除模式；
2. 实现纯 `RuleFirstIssueClassifier`，无匹配返回 `unclassified`，不能伪造主题；
3. 实现 `DataCellBuilder`：40 条反馈、60 分钟或 6000 估算 token 任一条件达到即关闭 Cell；
4. 在投影 Worker 内写 `issue_catalog`、`feedback_issue_link`、`data_cell` 和 `cell_issue`；写入必须与 WorkspaceProjection 的成功事务一致；
5. 为规则命中、未命中、Cell 边界、Workspace 隔离和幂等重试写测试；
6. 完成后再进入日指标、EWMA、z-score 与 Alert。

## 8. 不可突破的边界

- 不保存原始 CSV、真实工单号、手机号、邮箱或未脱敏文本到 PostgreSQL、报告、Trace 或模型输入；
- 不让 LLM 直接写数据库、创建 Alert 或修改基线；Qwen 接入尚未开始；
- 不将 Report 的重新生成与数据重建混为一谈；报告未来只能读取投影事实；
- 不在子类或 Controller 内手写跨层 SQL、主题计算或状态机；
- 不执行 `git reset --hard`、不删除用户已有的未提交修改。
