# InsightFlow 开发交接手册

> 最后更新：2026-07-22
> 当前分支：`feature/data-cell-rule-issue-merging`
> 远程仓库：`https://github.com/Suda-313/yuqiagent.git`

## 1. 给下一位开发者的任务说明

```text
请继续开发 D:\yuqiagent 的 InsightFlow 项目。先完整阅读 AGENTS.md、docs/HANDOFF.md 和本文件。

技术栈固定为 Java 17 + Spring Boot 3.5 + PostgreSQL 16 + Flyway + MinIO + Docker Compose；
保持模块化单体和 MVC 风格包结构，不改成 Python，不引入 Kafka、微服务或真实爬虫。

当前已完成全部核心功能：CSV 导入、自动投影、DataCell+规则分类、日指标聚合、EWMA基线、
z-score告警、Spring AI Agent层（DeepSeek-v4-flash）、看板API、报告API（含下载）。

每个新增业务、实体、任务和迁移模块的有效注释行数不得少于非空代码行数的 1/2；先写失败测试，
再写最小实现；不要提交或推送，除非用户明确要求。完成后运行 mvnw.cmd test 和 mvnw.cmd package。
```

## 2. 新电脑恢复步骤

1. 安装 JDK 17+、Docker Desktop（启用 WSL 2）和 Git；Maven 使用项目自带的 `mvnw.cmd`
2. 克隆仓库：
   ```powershell
   git clone https://github.com/Suda-313/yuqiagent.git D:\yuqiagent
   cd D:\yuqiagent
   git checkout feature/data-cell-rule-issue-merging
   ```
3. 从 `.env.example` 创建本地 `.env`，配置 API Key
4. 启动依赖：
   ```powershell
   docker compose up -d
   ```
5. 启动应用：
   ```powershell
   $env:DASHSCOPE_API_KEY = "sk-xxx"
   ./mvnw.cmd spring-boot:run
   ```
6. 访问 `http://localhost:8080/actuator/health`

## 3. 已完成能力

### 确定性管线
- **CSV 导入**：上传 → 表头预览 → 字段映射 → 异步导入 → 脱敏入库
- **自动投影**：导入成功 → 创建投影任务 → Scheduler 领取 → Worker 执行
- **DataCell 切分**：40 条/60 分钟/6000 token 三护栏
- **规则优先分类**：8 条种子规则，TOML 配置，最多 2 主题，未命中返回 unclassified
- **日指标聚合**：按 (issue, 日期) 聚合，source_kind 分布，UPSERT 幂等
- **EWMA 基线**：α=0.3，3 天基线建立，二维象限分类（surge/escalating/chronic/longtail/normal）
- **z-score 告警**：动态阈值 + 6 小时冷却期

### Agent 增强层
- **Spring AI 集成**：OpenAI 兼容模式对接阿里云百炼 DeepSeek-v4-flash
- **CellAnalysisAgent**：3 个并行 Analyzer（分类补充 + 情感分析 + 风险分析）
- **ReportAgent**：LLM 生成运营周报，支持 Markdown 下载
- **LLM 指标**：每次调用的 token 消耗和耗时日志

### REST API
- `GET /dashboard` — 看板摘要
- `GET /issues` — 主题列表
- `GET /issues/{key}` — 主题详情
- `POST /analysis-reports` — 创建报告
- `GET /analysis-reports/{id}` — 查询报告
- `GET /analysis-reports/{id}/download` — 下载报告

### 数据库
- Flyway V1-V8 迁移
- 19 张表，12 个 JPA Repository
- 74+ 个单元测试

## 4. 当前目录与关键文件

```text
src/main/java/com/insightflow/
  agent/                      # Agent 增强层（Spring AI）
    InsightAgent.java         # Agent 接口
    AgentOrchestrator.java    # 并行编排器
    AgentFallbackManager.java # 降级管理
    CellAnalysisAgent.java    # Cell 分析编排
    AgentAnalysisScheduler.java # 事件监听 + 异步调度
    LlmMetrics.java           # Token 指标
    analyzer/                 # 三个并行 Analyzer
    report/                   # 报告生成 + 对账引擎
    dto/                      # 结构化输出
    event/                    # 投影完成事件
  controller/                 # HTTP 边界
  service/
    analysis/                 # 分析管线（规则分类/切分/聚合/基线/告警）
    importing/                # CSV 解析/PII 脱敏/哈希
  task/                       # 异步任务（导入/投影/报告）
  entity/                     # JPA 实体（19 张表）
  repository/                 # Spring Data 仓储（12 个）
  config/                     # 配置（Agent/Analysis/Import/MinIO）
  storage/                    # MinIO 对象存储
  common/exception/           # 异常处理
  dto/                        # 传输对象
```

## 5. 数据库状态

Flyway 当前版本为 V8。不要修改 V1-V8；新增表或字段必须创建新的前向迁移。

## 6. 不可突破的边界

- 不保存原始 CSV、真实工单号、手机号、邮箱或未脱敏文本到 PostgreSQL
- 不让 LLM 直接写数据库、创建 Alert 或修改基线
- 不将 Report 的重新生成与数据重建混为一谈
- 不在子类或 Controller 内手写跨层 SQL、主题计算或状态机
- 不执行 `git reset --hard`、不删除用户已有的未提交修改