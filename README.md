# InsightFlow

> 游戏客服舆情分析系统 — 自动化导入、主题识别、趋势监控、异常告警、AI 报告生成

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.5 |
| AI | Spring AI + DeepSeek-v4-flash（阿里云百炼） |
| 数据库 | PostgreSQL 16 + pgvector |
| 缓存 | Redis 7 |
| 对象存储 | MinIO |
| 迁移 | Flyway V1-V8 |
| 部署 | Docker Compose |

## 快速启动

```powershell
# 1. 启动基础设施
docker compose up -d

# 2. 配置 API Key（可选，Agent 层需要）
$env:DASHSCOPE_API_KEY = "sk-xxx"

# 3. 启动应用
./mvnw.cmd spring-boot:run

# 4. 验证
curl http://localhost:8080/actuator/health
```

## 系统架构

```mermaid
graph TB
    subgraph "数据接入层"
        CSV[CSV 文件上传]
        MinIO[MinIO 对象存储]
    end

    subgraph "确定性管线 - 同步"
        Import[CSV 导入 + PII 脱敏]
        Rule[规则优先分类<br/>RuleFirstIssueClassifier]
        Cell[DataCell 切分<br/>40条/60min/6000token]
        Metric[日指标聚合<br/>MetricBucketService]
        EWMA[EWMA 基线<br/>α=0.3]
        Alert[z-score 告警<br/>冷却期 6h]
    end

    subgraph "Agent 增强层 - 异步"
        Event[ProjectionCompletedEvent]
        Scheduler[AgentAnalysisScheduler]
        ClassAnalyzer[ClassificationAnalyzer<br/>分类补充]
        SentAnalyzer[SentimentAnalyzer<br/>情感分析]
        RiskAnalyzer[RiskAnalyzer<br/>风险分析]
        ReportAgent[ReportAgent<br/>LLM 生成周报]
        Reconcile[ReconciliationEngine<br/>纯代码对账]
    end

    subgraph "API 层"
        Dashboard[GET /dashboard]
        Issues[GET /issues]
        ReportAPI[POST /analysis-reports]
        Download[GET /download]
    end

    CSV --> MinIO
    MinIO --> Import
    Import --> Rule
    Rule --> Cell
    Cell --> Metric
    Metric --> EWMA
    EWMA --> Alert
    Alert --> Event

    Event --> Scheduler
    Scheduler --> ClassAnalyzer
    Scheduler --> SentAnalyzer
    Scheduler --> RiskAnalyzer
    Scheduler --> ReportAgent
    ReportAgent --> Reconcile

    Metric --> Dashboard
    Alert --> Dashboard
    ReportAgent --> ReportAPI
    ReportAPI --> Download
```

## 数据流全链路

```mermaid
sequenceDiagram
    participant U as 用户
    participant API as REST API
    participant DB as PostgreSQL
    participant LLM as DeepSeek-v4-flash

    U->>API: 上传 CSV
    API->>DB: 脱敏入库
    Note over DB: FeedbackEvent

    DB->>DB: 自动投影任务
    Note over DB: RuleClassifier → 8 个主题
    Note over DB: DataCell → 113 个 Cell
    Note over DB: MetricBucket → 7 天×8 主题
    Note over DB: EWMA → 7 active
    Note over DB: Alert → login_failure 激增告警

    DB->>API: ProjectionCompletedEvent
    API->>LLM: CellAnalysisAgent (并行)
    LLM-->>API: 分类 + 情感 + 风险

    U->>API: POST /analysis-reports
    API->>DB: 创建报告任务
    DB->>LLM: ReportAgent
    LLM-->>DB: 运营周报
    U->>API: GET /download
    API-->>U: analysis-report.md
```

## 数据库 ER 图

```mermaid
erDiagram
    Workspace ||--o{ ImportFile : "导入"
    Workspace ||--o{ FeedbackEvent : "脱敏反馈"
    Workspace ||--o{ WorkspaceProjection : "投影"
    Workspace ||--o{ IssueCatalog : "主题"
    Workspace ||--o{ AnalysisReport : "报告"

    WorkspaceProjection ||--o{ DataCell : "切分"
    WorkspaceProjection ||--o{ FeedbackIssueLink : "关联"
    WorkspaceProjection ||--o{ IssueMetricBucket : "指标"
    WorkspaceProjection ||--o{ Alert : "告警"

    DataCell ||--o{ CellIssue : "计数"
    IssueCatalog ||--o{ FeedbackIssueLink : "关联"
    IssueCatalog ||--o{ IssueMetricBucket : "指标"
    IssueCatalog ||--o{ IssueBaselineProfile : "基线"
    IssueCatalog ||--o{ Alert : "告警"
    IssueCatalog ||--o{ IssueAlias : "别名"
```

## Agent 架构

```mermaid
graph LR
    subgraph "CellAnalysisAgent"
        CA[ClassificationAnalyzer<br/>分类补充]
        SA[SentimentAnalyzer<br/>情感分析]
        RA[RiskAnalyzer<br/>风险分析]
    end

    subgraph "ReportAgent"
        Gen[LLM 生成草稿]
        Rec[ReconciliationEngine<br/>纯代码对账]
        Fix[LLM 修正]
    end

    Cell[DataCell 文本] --> CA
    Cell --> SA
    Cell --> RA
    CA --> Merge[CellInsight 合并]
    SA --> Merge
    RA --> Merge

    Dashboard[聚合数据] --> Gen
    Gen --> Rec
    Rec -->|不通过| Fix
    Fix --> Rec
    Rec -->|通过| Report[最终报告]
```

## 项目结构

```
src/main/java/com/insightflow/
├── InsightFlowApplication.java
├── agent/                          # Agent 增强层
│   ├── InsightAgent.java           # Agent 接口
│   ├── AgentOrchestrator.java      # 并行编排器
│   ├── AgentFallbackManager.java   # 降级管理
│   ├── CellAnalysisAgent.java      # Cell 分析（3 Analyzer 并行）
│   ├── AgentAnalysisScheduler.java # 事件监听 + 异步调度
│   ├── LlmMetrics.java             # Token 指标
│   ├── analyzer/                   # 三个 Analyzer
│   │   ├── ClassificationAnalyzer.java
│   │   ├── SentimentAnalyzer.java
│   │   └── RiskAnalyzer.java
│   ├── report/                     # 报告生成
│   │   ├── ReportAgent.java
│   │   ├── ReconciliationEngine.java
│   │   └── ReportTools.java
│   ├── dto/                        # 结构化输出
│   └── event/                      # 领域事件
├── controller/                     # HTTP 边界
│   ├── WorkspaceController.java
│   ├── FileImportController.java
│   ├── DashboardController.java
│   └── ReportController.java
├── service/
│   ├── analysis/                   # 分析管线
│   │   ├── IssueRulesLoader.java
│   │   ├── IssueTextNormalizer.java
│   │   ├── RuleFirstIssueClassifier.java
│   │   ├── DataCellBuilder.java
│   │   ├── IssueCatalogService.java
│   │   ├── ProjectionSourceLoader.java
│   │   ├── ProjectionFactWriter.java
│   │   ├── MetricBucketService.java
│   │   ├── EwmaBaselineService.java
│   │   ├── AlertDetector.java
│   │   └── WorkspaceProjectionExecutionService.java
│   ├── importing/                  # CSV 导入
│   ├── DashboardService.java
│   ├── ReportCommandService.java
│   └── KnowledgeService.java
├── task/                           # 异步任务
│   ├── ImportTask*.java            # 导入调度
│   ├── WorkspaceProjection*.java   # 投影调度
│   └── AnalysisReport*.java        # 报告调度
├── entity/                         # JPA 实体（19 张表）
├── repository/                     # Spring Data 仓储（12 个）
├── config/                         # 配置
├── storage/                        # MinIO
├── common/exception/               # 异常处理
└── dto/                            # 传输对象
```

## API 文档

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/workspaces` | 创建工作区 |
| POST | `/api/v1/workspaces/{id}/imports/files` | 上传 CSV |
| POST | `/api/v1/workspaces/{id}/imports/files/{fid}/mapping` | 保存字段映射 |
| POST | `/api/v1/workspaces/{id}/imports/files/{fid}/start` | 启动导入 |
| GET | `/api/v1/workspaces/{id}/dashboard` | 看板摘要 |
| GET | `/api/v1/workspaces/{id}/issues` | 主题列表 |
| GET | `/api/v1/workspaces/{id}/issues/{key}` | 主题详情 |
| POST | `/api/v1/workspaces/{id}/analysis-reports` | 创建报告 |
| GET | `/api/v1/workspaces/{id}/analysis-reports/{rid}` | 查询报告 |
| GET | `/api/v1/workspaces/{id}/analysis-reports/{rid}/download` | 下载报告 |

## 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `DASHSCOPE_API_KEY` | — | 阿里云百炼 API Key |
| `spring.ai.openai.model` | `deepseek-v4-flash` | LLM 模型 |
| `insightflow.agent.enabled` | `true` | Agent 开关 |
| `insightflow.analysis.ewma-alpha` | `0.3` | EWMA 平滑因子 |
| `insightflow.analysis.alert-cooldown-hours` | `6` | 告警冷却期 |

## 测试

```powershell
# 运行全部测试
./mvnw.cmd test

# 运行特定测试
./mvnw.cmd test -Dtest=MetricBucketServiceTest
```

## 开发进度

| 阶段 | 功能 | 状态 |
|------|------|------|
| Phase 1 | CSV 导入 + PII 脱敏 | ✅ |
| Phase 2 | DataCell + 规则分类 | ✅ |
| Phase 3A | 日指标聚合 | ✅ |
| Phase 3B | EWMA 基线 + 告警 | ✅ |
| Phase 4 | Agent 层 + Spring AI | ✅ |
| Phase 5 | 看板 API + 报告 API | ✅ |
| 待定 | 前端页面 | ❌ |
| 待定 | RAG 向量搜索 | ❌ |
| 待定 | main 分支合并 | ❌ |