# InsightFlow Agent 层设计

> 状态：待用户复核
> 日期：2026-07-21
> 分支：feature/data-cell-rule-issue-merging
> 前置文档：`docs/superpowers/specs/2026-07-21-ewma-alert-design.md`
> 参考项目：`D:\ticket_automation_system-main`（CellProducer/ReportComposer）、BettaFish（多 Agent 并行 + 反思）、Claude Code（Agent Harness 模式）

## 1. 目标与边界

在现有确定性投影管线上，新增 Spring AI 驱动的 Agent 增强层，实现：

1. **LLM 客户端**（阿里云 DashScope Qwen3-Plus）
2. **Agent Harness 抽象**（Agent 接口 + Orchestrator 编排）
3. **CellAnalysisAgent**（三个并行 Analyzer：分类补充 + 情感 + 风险）
4. **ReportAgent**（LLM 生成 + 纯代码对账 + LLM 修正）
5. **RAG 准备**（pgvector 知识库，手动录入 + 自动积累）

**本期不实现：**
- 真实 Embedding 模型调用（RAG 仅预留接口和表结构）
- 多模态分析
- Agent 间辩论/论坛
- Web 前端

## 2. 核心决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 模型 | Qwen3-Plus（统一） | 分类+情感+风险+报告都用同一模型，简化配置 |
| 触发时机 | 投影后异步 | 投影是核心路径，Agent 是增强层，不能阻塞 |
| CellAnalysis | 三个并行 Analyzer | 关注点分离，独立可测，失败隔离 |
| 报告对账 | 纯代码 | 数字不走 LLM，可靠 |
| 报告修正 | LLM 修正 | 对账发现错误后，LLM 改写文字，再对账 |
| RAG 初始 | 手动录入 + 自动积累 | 先做 Web 录入界面，后续自动从告警处理积累 |

## 3. 架构总览

```text
                      InsightFlow 全貌

确定性管线（同步，毫秒级）          Agent 增强层（异步，秒级）
─────────────────────────        ─────────────────────────
CSV 导入                          Spring AI DashScope
  ↓                               ┌─ Agent Harness ──────┐
投影完成（发布事件）                │ Agent 接口 + 编排器    │
  ↓                               │ 并行调度 + 降级兜底    │
RuleClassifier ─┐                 └──────────────────────┘
  → 命中 → link  │                 ┌─ CellAnalysisAgent ──┐
  → 未命中 ─────┼──→ 异步触发 ──→  │ ClassificationAnalyzer │
                │                 │ SentimentAnalyzer      │
MetricBucket ←──┘                 │ RiskAnalyzer           │
EWMA Baseline                     └────────────────────────┘
Alert Detector                    ┌─ ReportAgent ─────────┐
                                  │ Step 1: LLM 生成草稿   │
用户点击"生成报告" ──→ 按需触发 ──→ │ Step 2: 纯代码对账     │
                                  │ Step 3: LLM 修正       │
                                  │ Step 4: 输出终稿       │
                                  └────────────────────────┘
                                  ┌─ RAG 知识库 ──────────┐
                                  │ pgvector + 运维录入    │
                                  │ 历史告警自动积累       │
                                  └────────────────────────┘
```

## 4. Spring AI 技术栈

### 4.1 依赖（pom.xml）

```xml
<!-- Spring AI BOM -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- DashScope 适配器 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-dashscope-spring-boot-starter</artifactId>
</dependency>
```

### 4.2 配置（application.yml）

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:}
      chat:
        model: qwen-plus
        options:
          temperature: 0.3
          max-tokens: 2000

insightflow:
  agent:
    enabled: true
    max-retries: 2
    timeout-seconds: 30
```

## 5. Agent Harness 抽象

### 5.1 Agent 接口

```java
/**
 * Agent 抽象：每个 Agent 有自己的 system prompt、tool 集合和输出 schema。
 * 借鉴 Claude Code Harness 的 Agent 类型注册模式。
 */
public interface InsightAgent<T> {
    /** 系统提示词，定义 Agent 的角色和能力。 */
    String systemPrompt();

    /** 可用的工具集合；无工具返回空列表。 */
    default List<ToolCallback> tools() { return List.of(); }

    /** 结构化输出的类型，用于 JSON Schema 约束。 */
    Class<T> outputSchema();

    /** 执行 Agent，输入用户文本，返回结构化输出。 */
    T execute(String userInput);
}
```

### 5.2 Orchestrator 编排器

```java
/**
 * Agent 编排器：借鉴 Claude Code 的 parallel() 和 pipeline() 函数。
 * 支持并行执行多个 Agent、管道式执行、失败降级。
 */
@Component
public class AgentOrchestrator {

    /** 并行执行：多个 Agent 同时跑，返回结果列表（null 表示失败）。 */
    public <T> List<T> parallel(List<InsightAgent<T>> agents, String input);

    /** 管道执行：前一个 Agent 的输出作为后一个的输入。 */
    public <T> T pipeline(List<InsightAgent<?>> agents, String input);
}
```

### 5.3 降级策略

```java
/**
 * Agent 降级：LLM 失败时退回到规则引擎。
 * 借鉴 Claude Code 的 agent 失败返回 null 模式。
 */
@Component
public class AgentFallbackManager {
    /** LLM 调用失败时，用规则引擎生成降级结果。 */
    public <T> T fallback(InsightAgent<T> agent, String input);
}
```

## 6. CellAnalysisAgent

### 6.1 触发时机

投影完成后，`WorkspaceProjectionCompletionService` 发布 `ProjectionCompletedEvent`。`AgentAnalysisScheduler` 监听事件，异步触发 CellAnalysisAgent。

```text
投影完成 → 发布 ProjectionCompletedEvent
              ↓
AgentAnalysisScheduler（@Async）
  ├─ 查本次投影的 data_cell 列表
  ├─ 对每个 Cell 调用 CellAnalysisAgent
  └─ 结果写入 cell_issue 扩展字段
```

### 6.2 三个并行 Analyzer

```java
// 三个 Analyzer 共享同一个 ChatClient 但各有独立 prompt

@Component
class ClassificationAnalyzer implements InsightAgent<ClassificationResult> {
    // System Prompt: "你是游戏客服工单分类助手。根据工单文本，判断它属于哪个已知问题类别..."
    // Output Schema: { canonicalKey: string, confidence: double, reasoning: string }
    // 只处理 RuleClassifier 未命中的事件
}

@Component
class SentimentAnalyzer implements InsightAgent<SentimentResult> {
    // System Prompt: "你是游戏客服情感分析助手。判断玩家情绪和紧急程度..."
    // Output Schema: { sentiment: "positive|neutral|negative|angry", urgency: "low|medium|high|critical", keywords: [] }
}

@Component
class RiskAnalyzer implements InsightAgent<RiskResult> {
    // System Prompt: "你是游戏运营风险分析助手。判断该反馈是否存在公关危机风险..."
    // Output Schema: { riskLevel: "none|low|medium|high", crisisPotential: double, riskReasons: [] }
}
```

### 6.3 合并逻辑（Synthesizer，纯代码，非 Agent）

```java
// 并行调用三个 Analyzer，合并结果
public CellInsight analyze(DataCell cell) {
    var futures = List.of(
        CompletableFuture.supplyAsync(() -> classificationAnalyzer.execute(cellText)),
        CompletableFuture.supplyAsync(() -> sentimentAnalyzer.execute(cellText)),
        CompletableFuture.supplyAsync(() -> riskAnalyzer.execute(cellText))
    );
    // 等待全部完成，任一失败不影响其他
    return new CellInsight(
        futures.get(0).resultNow(),  // null if failed
        futures.get(1).resultNow(),
        futures.get(2).resultNow()
    );
}
```

## 7. ReportAgent

### 7.1 触发时机

用户通过 API 请求生成报告时按需触发。

### 7.2 四步流程

```text
POST /api/v1/workspaces/{id}/analysis-reports
  → 创建 analysis_report(AsyncTask)
  → AgentAnalysisScheduler 领取租约
  → ReportAgent 执行

ReportAgent 四步流程：
  Step 1: LLM 生成草稿
    输入: 聚合指标 + 告警 + 基线 + 主题 Top N + 脱敏样本
    输出: { executive_summary, highlights, recommendations, risk_alerts }
  
  Step 2: 纯代码对账（ReconciliationEngine）
    - 正则提取 summary 中的数字
    - 与确定性数据比对
    - risk_alerts[].mentions 不超过 ticket_count
    - 不一致 → 记录到 reconciliation 审计

  Step 3: LLM 修正
    输入: 草稿 + 对账报告（指出哪些数字错了）
    输出: 修正后的报告

  Step 4: 再次对账 → 通过 → 写入 analysis_report.report_json
```

### 7.3 Tool Calling

ReportAgent 可以调用工具查询数据库（不预拼数据到 prompt）：

```java
@Component
class ReportTools {
    @Tool(description = "查询某主题的日指标趋势")
    List<MetricBucket> getMetricTrend(
        @ToolParam(description = "主题 canonical_key") String issueKey,
        @ToolParam(description = "查询天数") int days);

    @Tool(description = "查询最近的告警记录")
    List<Alert> getRecentAlerts(
        @ToolParam(description = "返回条数") int limit);

    @Tool(description = "搜索知识库中相关的历史事件")
    String searchKnowledgeBase(
        @ToolParam(description = "搜索关键词") String query);
}
```

## 8. RAG 知识库

### 8.1 表结构（V8 迁移）

```sql
-- 知识库条目
CREATE TABLE knowledge_entry (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    workspace_id BIGINT NOT NULL REFERENCES workspace(id),
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(50) NOT NULL,  -- version_log, alert_solution, faq, sop
    source VARCHAR(30) NOT NULL,    -- manual, auto
    embedding vector(1536),          -- pgvector，Qwen3 embedding
    metadata_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_embedding ON knowledge_entry
    USING ivfflat (embedding vector_cosine_ops);
```

### 8.2 内容来源

| 来源 | 触发方式 | 示例 |
|------|---------|------|
| 手动录入 | Web 界面 | 运营录入版本更新日志 |
| 自动积累 | 告警处理后 | 告警 → 解决方案自动入库 |
| 系统导入 | API | 批量导入历史 FAQ |

### 8.3 本期实现范围

- 表结构（V8 迁移）
- `KnowledgeEntryRepository`（CRUD）
- `KnowledgeService`（searchByVector, insertManual, insertAuto）
- 预留 `VectorStore` Bean（Spring AI `PgVectorStore`），等 Embedding API 接入后激活

## 9. 新增/修改文件清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `pom.xml` | 加 Spring AI BOM + DashScope starter |
| 修改 | `application.yml` | 加 spring.ai 和 insightflow.agent 配置 |
| 新增 | `config/AgentConfiguration.java` | ChatClient Bean + Agent Bean 装配 |
| 新增 | `agent/InsightAgent.java` | Agent 接口 |
| 新增 | `agent/AgentOrchestrator.java` | 编排器 |
| 新增 | `agent/AgentFallbackManager.java` | 降级管理 |
| 新增 | `agent/analyzer/ClassificationAnalyzer.java` | 分类补充 |
| 新增 | `agent/analyzer/SentimentAnalyzer.java` | 情感分析 |
| 新增 | `agent/analyzer/RiskAnalyzer.java` | 风险分析 |
| 新增 | `agent/CellAnalysisAgent.java` | Cell 分析编排（并行三个 Analyzer） |
| 新增 | `agent/AgentAnalysisScheduler.java` | 监听投影完成事件，异步触发 Agent |
| 新增 | `agent/report/ReportAgent.java` | 报告生成（四步流程） |
| 新增 | `agent/report/ReconciliationEngine.java` | 纯代码对账 |
| 新增 | `agent/report/ReportTools.java` | 报告工具调用 |
| 新增 | `agent/event/ProjectionCompletedEvent.java` | 投影完成事件 |
| 新增 | `entity/KnowledgeEntry.java` | 知识库实体 |
| 新增 | `repository/KnowledgeEntryRepository.java` | 知识库仓储 |
| 新增 | `service/KnowledgeService.java` | 知识库 CRUD + 向量搜索 |
| 新增 | `db/migration/V8__add_knowledge_entry.sql` | 知识库表 |
| 新增 | 测试 | 各 Analyzer 测试 + ReportAgent 测试 + Reconciliation 测试 |

## 10. 测试策略

| 测试类 | 覆盖 |
|--------|------|
| `ClassificationAnalyzerTest` | 命中已知主题、未命中、LLM 超时降级 |
| `SentimentAnalyzerTest` | 情感判断、紧急度判断 |
| `RiskAnalyzerTest` | 风险等级、危机潜势 |
| `CellAnalysisAgentTest` | 三个 Analyzer 并行、部分失败、空 Cell |
| `ReconciliationEngineTest` | 数字提取、比对、修正 |
| `ReportAgentTest` | 四步流程、对账通过、对账不通过修正 |
| `AgentOrchestratorTest` | 并行执行、管道执行、降级触发 |
| `KnowledgeServiceTest` | 向量搜索（mock）、CRUD |

## 11. 非目标

- 不实现真实 Embedding 调用（RAG 仅预留接口）
- 不实现 Agent 辩论/论坛
- 不实现多模态分析
- 不实现 Web 前端
- 不修改确定性管线的已有代码（仅新增事件发布）
- 不修改 V1–V7 迁移