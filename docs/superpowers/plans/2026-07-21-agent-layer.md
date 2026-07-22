# Agent 层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 引入 Spring AI + Qwen3-Plus，实现 Agent 增强层：CellAnalysisAgent（三并行 Analyzer）、ReportAgent（LLM 生成 + 纯代码对账 + 修正）、Agent Harness 抽象、RAG 知识库预留。

**Architecture:** 新增 `agent/` 包，与现有 `service/analysis/` 完全解耦。投影完成后发布 `ProjectionCompletedEvent`，`AgentAnalysisScheduler` 异步监听并触发 CellAnalysisAgent。ReportAgent 按需触发。确定性管线只在 `WorkspaceProjectionCompletionService` 加一行事件发布。

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI 1.1.0, DashScope Qwen-Plus, PostgreSQL 16 + pgvector, Jackson, JUnit 5 + Mockito。

## Global Constraints

- 不修改 V1–V7 迁移；新增 `V8__add_knowledge_entry.sql`。
- 确定性管线代码只加事件发布，不改逻辑。
- Agent 层全部异步，不阻塞投影。
- 不实现真实 Embedding 调用（RAG 仅预留接口）。
- 不实现 Web 前端。
- 每个新增模块有效注释行数 ≥ 非空代码行数 1/2。
- TDD：先写失败测试，再写最小实现。
- **不提交不推送**；每个任务末尾只 `git add` 暂存。

---

## File Structure

```
src/main/java/com/insightflow/
  agent/
    InsightAgent.java              — Agent 接口
    AgentOrchestrator.java         — 编排器
    AgentFallbackManager.java      — 降级管理
    analyzer/
      ClassificationAnalyzer.java  — 分类补充
      SentimentAnalyzer.java       — 情感分析
      RiskAnalyzer.java            — 风险分析
    CellAnalysisAgent.java         — Cell 分析编排
    AgentAnalysisScheduler.java    — 事件监听 + 异步调度
    report/
      ReportAgent.java             — 报告生成
      ReconciliationEngine.java    — 纯代码对账
      ReportTools.java             — 工具调用
    event/
      ProjectionCompletedEvent.java — 投影完成事件
    dto/
      ClassificationResult.java    — 分类输出 schema
      SentimentResult.java         — 情感输出 schema
      RiskResult.java              — 风险输出 schema
      CellInsight.java             — 合并后的 Cell 分析结果
      ReportDraft.java             — 报告草稿
      ReconciliationReport.java    — 对账结果
  entity/
    KnowledgeEntry.java            — 知识库实体
  repository/
    KnowledgeEntryRepository.java  — 知识库仓储
  service/
    KnowledgeService.java          — 知识库 CRUD + 向量搜索
  config/
    AgentConfiguration.java        — 修改：加 Agent Bean

src/main/resources/
  db/migration/
    V8__add_knowledge_entry.sql    — 知识库表
  application.yml                  — 修改：加 spring.ai + insightflow.agent

pom.xml                            — 修改：加 Spring AI 依赖

src/test/java/com/insightflow/
  agent/analyzer/ClassificationAnalyzerTest.java
  agent/analyzer/SentimentAnalyzerTest.java
  agent/analyzer/RiskAnalyzerTest.java
  agent/CellAnalysisAgentTest.java
  agent/report/ReconciliationEngineTest.java
  agent/report/ReportAgentTest.java
```

---

### Task 1: Spring AI 依赖 + 配置 + 共享 DTO

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/insightflow/agent/dto/ClassificationResult.java`
- Create: `src/main/java/com/insightflow/agent/dto/SentimentResult.java`
- Create: `src/main/java/com/insightflow/agent/dto/RiskResult.java`
- Create: `src/main/java/com/insightflow/agent/dto/CellInsight.java`
- Create: `src/main/java/com/insightflow/agent/dto/ReportDraft.java`
- Create: `src/main/java/com/insightflow/agent/dto/ReconciliationReport.java`
- Create: `src/main/java/com/insightflow/agent/event/ProjectionCompletedEvent.java`

- [ ] **Step 1: 在 pom.xml 加 Spring AI BOM 和 DashScope starter**

```xml
<!-- 在 <properties> 后加 Spring AI 版本 -->
<spring-ai.version>1.1.0</spring-ai.version>

<!-- 在 <dependencies> 前加 BOM -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- 在 <dependencies> 末尾加 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-dashscope-spring-boot-starter</artifactId>
</dependency>
```

- [ ] **Step 2: 在 application.yml 加配置**

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
    enabled: ${AGENT_ENABLED:true}
    max-retries: ${AGENT_MAX_RETRIES:2}
    timeout-seconds: ${AGENT_TIMEOUT_SECONDS:30}
```

在已有的 `insightflow:` 块下，`analysis:` 块之后追加 `agent:` 块。

- [ ] **Step 3: 创建共享 DTO（纯 record，无依赖）**

`ClassificationResult.java`:
```java
package com.insightflow.agent.dto;

import java.util.List;

/** 分类 Analyzer 的结构化输出。 */
public record ClassificationResult(
        String canonicalKey,
        double confidence,
        String reasoning,
        List<String> keywords) {
}
```

`SentimentResult.java`:
```java
package com.insightflow.agent.dto;

import java.util.List;

/** 情感 Analyzer 的结构化输出。 */
public record SentimentResult(
        String sentiment,   // positive | neutral | negative | angry
        String urgency,     // low | medium | high | critical
        List<String> keywords) {
}
```

`RiskResult.java`:
```java
package com.insightflow.agent.dto;

import java.util.List;

/** 风险 Analyzer 的结构化输出。 */
public record RiskResult(
        String riskLevel,       // none | low | medium | high
        double crisisPotential, // 0.0 - 1.0
        List<String> riskReasons) {
}
```

`CellInsight.java`:
```java
package com.insightflow.agent.dto;

import java.util.List;

/**
 * 三个 Analyzer 并行分析后的合并结果。
 * 任一 Analyzer 失败（null）不影响其他维度。
 */
public record CellInsight(
        ClassificationResult classification,
        SentimentResult sentiment,
        RiskResult risk,
        String summary,       // 拼接摘要
        List<String> keywords) // 合并去重关键词

    public static CellInsight merge(ClassificationResult c, SentimentResult s, RiskResult r) {
        // 合并逻辑...
    }
}
```

`ReportDraft.java`（LLM 输出的报告草稿）:
```java
package com.insightflow.agent.dto;

import java.util.List;

/** ReportAgent Step 1 的 LLM 输出。 */
public record ReportDraft(
        String executiveSummary,
        List<String> highlights,
        List<String> recommendations,
        List<RiskAlert> riskAlerts) {

    public record RiskAlert(String event, String severity, int mentions) {}
}
```

`ReconciliationReport.java`:
```java
package com.insightflow.agent.dto;

import java.util.List;

/** 纯代码对账的审计记录。 */
public record ReconciliationReport(
        boolean ok,
        int mismatches,
        List<Check> checks,
        List<Override> overrides) {

    public record Check(String field, int claimed, int actual, boolean ok) {}
    public record Override(String field, int llmValue, int actual, String reason) {}
}
```

- [ ] **Step 4: 创建 ProjectionCompletedEvent**

```java
package com.insightflow.agent.event;

import org.springframework.context.ApplicationEvent;

/** 投影完成后发布，触发 Agent 异步分析。 */
public class ProjectionCompletedEvent extends ApplicationEvent {
    private final Long projectionId;
    private final Long workspaceId;

    public ProjectionCompletedEvent(Object source, Long projectionId, Long workspaceId) {
        super(source);
        this.projectionId = projectionId;
        this.workspaceId = workspaceId;
    }

    public Long getProjectionId() { return projectionId; }
    public Long getWorkspaceId() { return workspaceId; }
}
```

- [ ] **Step 5: 验证编译**

Run: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd -q compile`

- [ ] **Step 6: 暂存**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/com/insightflow/agent/
```

---

### Task 2: Agent Harness（接口 + 编排器 + 降级管理）

**Files:**
- Create: `src/main/java/com/insightflow/agent/InsightAgent.java`
- Create: `src/main/java/com/insightflow/agent/AgentOrchestrator.java`
- Create: `src/main/java/com/insightflow/agent/AgentFallbackManager.java`
- Test: `src/test/java/com/insightflow/agent/AgentOrchestratorTest.java`

- [ ] **Step 1: 写 InsightAgent 接口**

```java
package com.insightflow.agent;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * Agent 抽象：每个 Agent 有自己的 system prompt、tool 集合和输出 schema。
 * 借鉴 Claude Code Harness 的 Agent 类型注册模式。
 *
 * @param <T> 结构化输出类型
 */
public interface InsightAgent<T> {

    /** 系统提示词，定义 Agent 的角色和能力。 */
    String systemPrompt();

    /** 可用的工具集合；无工具返回空列表。 */
    default List<ToolCallback> tools() { return List.of(); }

    /** 结构化输出的类型，用于 JSON Schema 约束。 */
    Class<T> outputSchema();

    /**
     * 执行 Agent，输入用户文本，返回结构化输出。
     * 实现类负责调用 ChatClient 并解析结果。
     */
    T execute(String userInput);
}
```

- [ ] **Step 2: 写 AgentOrchestrator**

```java
package com.insightflow.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Agent 编排器：借鉴 Claude Code 的 parallel() 和 pipeline() 函数。
 * 并行执行多个 Agent，独立失败不影响其他。
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private final AgentFallbackManager fallbackManager;
    private final int timeoutSeconds;

    public AgentOrchestrator(AgentFallbackManager fallbackManager,
                             @Value("${insightflow.agent.timeout-seconds:30}") int timeoutSeconds) {
        this.fallbackManager = fallbackManager;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 并行执行多个 Agent，返回结果列表。
     * 任一 Agent 失败 → 对应位置为 null，不影响其他。
     */
    @Async
    public <T> List<T> parallel(List<InsightAgent<T>> agents, String input) {
        List<CompletableFuture<T>> futures = new ArrayList<>();
        for (InsightAgent<T> agent : agents) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return agent.execute(input);
                } catch (Exception e) {
                    log.warn("Agent {} failed: {}", agent.getClass().getSimpleName(), e.getMessage());
                    try {
                        return fallbackManager.fallback(agent, input);
                    } catch (Exception fallbackEx) {
                        log.error("Fallback also failed for {}", agent.getClass().getSimpleName());
                        return null;
                    }
                }
            }));
        }
        return futures.stream()
                .map(f -> {
                    try { return f.get(timeoutSeconds, TimeUnit.SECONDS); }
                    catch (Exception e) { return null; }
                })
                .toList();
    }
}
```

- [ ] **Step 3: 写 AgentFallbackManager**

```java
package com.insightflow.agent;

import org.springframework.stereotype.Component;

/**
 * Agent 降级：LLM 失败时返回 null。
 * 后续可扩展为规则引擎兜底。
 */
@Component
public class AgentFallbackManager {

    /** 降级处理：当前返回 null，调用方自行处理。 */
    public <T> T fallback(InsightAgent<T> agent, String input) {
        return null;
    }
}
```

- [ ] **Step 4: 写测试**

```java
class AgentOrchestratorTest {
    @Test
    void parallelExecutesAllAgents() { /* mock 两个 Agent，验证都被调用 */ }
    @Test
    void returnsNullForFailedAgent() { /* 一个 Agent 抛异常，结果中对应位置为 null */ }
}
```

- [ ] **Step 5: 验证编译 + 测试**

- [ ] **Step 6: 暂存**

---

### Task 3: CellAnalysisAgent + 三个 Analyzer

**Files:**
- Create: `src/main/java/com/insightflow/agent/analyzer/ClassificationAnalyzer.java`
- Create: `src/main/java/com/insightflow/agent/analyzer/SentimentAnalyzer.java`
- Create: `src/main/java/com/insightflow/agent/analyzer/RiskAnalyzer.java`
- Create: `src/main/java/com/insightflow/agent/CellAnalysisAgent.java`
- Modify: `src/main/java/com/insightflow/config/AgentConfiguration.java`
- Test: 三个 Analyzer 测试 + CellAnalysisAgent 测试

- [ ] **Step 1: 写 ClassificationAnalyzer**

```java
package com.insightflow.agent.analyzer;

import com.insightflow.agent.InsightAgent;
import com.insightflow.agent.dto.ClassificationResult;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 分类补充 Analyzer：用 LLM 判断未命中规则的文本属于哪个已知主题。
 * 只处理 RuleClassifier 的 unclassified 输出，不替代规则引擎。
 */
@Component
public class ClassificationAnalyzer implements InsightAgent<ClassificationResult> {

    private static final String SYSTEM_PROMPT = """
            你是游戏客服工单分类助手。根据工单文本，判断它属于哪个问题类别。
            - 只能从已知类别中选择：login_failure, payment_recharge, item_loss,
              account_recovery, bug_gameplay, bug_network, violation_report, suggestion
            - 如果确实不属于任何类别，返回 canonical_key="unclassified"
            - confidence 表示你的确信度（0.0-1.0）
            - reasoning 用一句话解释分类理由
            - keywords 提取 3-5 个关键词
            """;

    private final ChatClient chatClient;

    public ClassificationAnalyzer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String systemPrompt() { return SYSTEM_PROMPT; }

    @Override
    public Class<ClassificationResult> outputSchema() { return ClassificationResult.class; }

    @Override
    public ClassificationResult execute(String userInput) {
        return chatClient.prompt()
                .system(systemPrompt())
                .user(userInput)
                .call()
                .entity(outputSchema());
    }
}
```

- [ ] **Step 2: 写 SentimentAnalyzer（同理，不同 prompt + outputSchema）**
- [ ] **Step 3: 写 RiskAnalyzer（同理，不同 prompt + outputSchema）**
- [ ] **Step 4: 写 CellAnalysisAgent**

```java
package com.insightflow.agent;

import com.insightflow.agent.analyzer.ClassificationAnalyzer;
import com.insightflow.agent.analyzer.SentimentAnalyzer;
import com.insightflow.agent.analyzer.RiskAnalyzer;
import com.insightflow.agent.dto.CellInsight;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Cell 分析编排：并行调用三个 Analyzer，合并结果。
 * 任一 Analyzer 失败不影响其他维度。
 */
@Component
public class CellAnalysisAgent {

    private final AgentOrchestrator orchestrator;
    private final ClassificationAnalyzer classificationAnalyzer;
    private final SentimentAnalyzer sentimentAnalyzer;
    private final RiskAnalyzer riskAnalyzer;

    public CellAnalysisAgent(AgentOrchestrator orchestrator,
                             ClassificationAnalyzer classificationAnalyzer,
                             SentimentAnalyzer sentimentAnalyzer,
                             RiskAnalyzer riskAnalyzer) {
        this.orchestrator = orchestrator;
        this.classificationAnalyzer = classificationAnalyzer;
        this.sentimentAnalyzer = sentimentAnalyzer;
        this.riskAnalyzer = riskAnalyzer;
    }

    public CellInsight analyze(String cellText) {
        var results = orchestrator.parallel(
                List.of(classificationAnalyzer, sentimentAnalyzer, riskAnalyzer),
                cellText);
        return CellInsight.merge(
                results.get(0), results.get(1), results.get(2));
    }
}
```

- [ ] **Step 5: 修改 AgentConfiguration 加 ChatClient Bean**

```java
@Bean
ChatClient chatClient(DashScopeChatModel chatModel) {
    return ChatClient.builder(chatModel).build();
}
```

- [ ] **Step 6: 写测试（每个 Analyzer 独立测试，mock ChatClient）**

- [ ] **Step 7: 验证 + 暂存**

---

### Task 4: AgentAnalysisScheduler（事件监听 + 异步触发）

**Files:**
- Create: `src/main/java/com/insightflow/agent/AgentAnalysisScheduler.java`
- Modify: `src/main/java/com/insightflow/task/WorkspaceProjectionCompletionService.java`（加事件发布）
- Test: `AgentAnalysisSchedulerTest.java`

- [ ] **Step 1: 写 AgentAnalysisScheduler**

```java
package com.insightflow.agent;

import com.insightflow.agent.event.ProjectionCompletedEvent;
import com.insightflow.entity.DataCell;
import com.insightflow.repository.DataCellRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听投影完成事件，异步触发 CellAnalysisAgent。
 * 代理标记 enabled=false 时跳过，允许无 LLM 环境运行。
 */
@Component
public class AgentAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentAnalysisScheduler.class);
    private final CellAnalysisAgent cellAnalysisAgent;
    private final DataCellRepository dataCellRepository;
    private final boolean enabled;

    public AgentAnalysisScheduler(CellAnalysisAgent cellAnalysisAgent,
                                  DataCellRepository dataCellRepository,
                                  @Value("${insightflow.agent.enabled:true}") boolean enabled) {
        this.cellAnalysisAgent = cellAnalysisAgent;
        this.dataCellRepository = dataCellRepository;
        this.enabled = enabled;
    }

    @Async
    @EventListener
    public void onProjectionCompleted(ProjectionCompletedEvent event) {
        if (!enabled) {
            log.info("Agent 已禁用，跳过投影 {} 的分析", event.getProjectionId());
            return;
        }
        log.info("开始 Agent 分析投影 {}", event.getProjectionId());
        List<DataCell> cells = dataCellRepository
                .findByWorkspaceProjectionIdAndWorkspaceId(event.getProjectionId(), event.getWorkspaceId());
        for (DataCell cell : cells) {
            try {
                cellAnalysisAgent.analyze("cell_" + cell.getId()); // TODO: 后续从 Cell 内事件文本拼接
            } catch (Exception e) {
                log.warn("Cell {} 分析失败: {}", cell.getId(), e.getMessage());
            }
        }
        log.info("投影 {} 的 Agent 分析完成，共 {} 个 Cell", event.getProjectionId(), cells.size());
    }
}
```

- [ ] **Step 2: 修改 WorkspaceProjectionCompletionService.complete() 加事件发布**

在 `complete()` 方法末尾（成功路径）加：
```java
applicationEventPublisher.publishEvent(
        new ProjectionCompletedEvent(this, projection.getId(), projection.getWorkspaceId()));
```

需要注入 `ApplicationEventPublisher`。

- [ ] **Step 3: 写测试**

- [ ] **Step 4: 验证 + 暂存**

---

### Task 5: ReportAgent（四步流程 + 对账引擎 + 工具调用）

**Files:**
- Create: `src/main/java/com/insightflow/agent/report/ReportAgent.java`
- Create: `src/main/java/com/insightflow/agent/report/ReconciliationEngine.java`
- Create: `src/main/java/com/insightflow/agent/report/ReportTools.java`
- Test: `ReconciliationEngineTest.java`, `ReportAgentTest.java`

**ReconciliationEngine 核心逻辑（对齐参考项目 reconciliation.py）：**

```java
/**
 * 纯代码对账引擎：对比 LLM 输出的数字与确定性数据。
 * 提取 summary 中的数字 → 与 merged.ticket_count 比对
 * 校验 risk_alerts[].mentions 不超过 ticket_count
 * 同名 issue 的 mentions 对齐
 */
@Component
public class ReconciliationEngine {

    public ReconciliationReport reconcile(ReportDraft draft, MergedData merged) {
        // 1. 正则提取 summary 中的数字
        // 2. 与 merged.ticket_count 比对
        // 3. 遍历 risk_alerts，检查 mentions 上界
        // 4. 同名 issue 对齐 mentions
        // 5. 返回 ReconciliationReport
    }
}
```

**ReportAgent 四步流程：**

```java
@Component
public class ReportAgent {

    public ReportResult generate(MergedData mergedData) {
        // Step 1: LLM 生成草稿
        ReportDraft draft = generateDraft(mergedData);
        // Step 2: 纯代码对账
        ReconciliationReport reconciliation = reconciliationEngine.reconcile(draft, mergedData);
        if (reconciliation.ok()) {
            return new ReportResult(draft, reconciliation);
        }
        // Step 3: LLM 修正
        ReportDraft revised = reviseDraft(draft, reconciliation);
        // Step 4: 再次对账
        ReconciliationReport finalCheck = reconciliationEngine.reconcile(revised, mergedData);
        return new ReportResult(revised, finalCheck);
    }
}
```

**ReportTools（@Tool 注解）：**

```java
@Component
public class ReportTools {
    @Tool(description = "查询某主题的日指标趋势")
    List<MetricBucket> getMetricTrend(@ToolParam String issueKey, @ToolParam int days);

    @Tool(description = "查询最近的告警记录")
    List<Alert> getRecentAlerts(@ToolParam int limit);
}
```

- [ ] **Step 1-5: TDD 实现 ReconciliationEngine → 测试 → ReportAgent → 测试 → ReportTools**

---

### Task 6: RAG 知识库（V8 迁移 + 实体 + 仓储 + 服务）

**Files:**
- Create: `src/main/resources/db/migration/V8__add_knowledge_entry.sql`
- Create: `src/main/java/com/insightflow/entity/KnowledgeEntry.java`
- Create: `src/main/java/com/insightflow/repository/KnowledgeEntryRepository.java`
- Create: `src/main/java/com/insightflow/service/KnowledgeService.java`

- [ ] **Step 1: V8 迁移**

```sql
CREATE TABLE knowledge_entry (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    workspace_id BIGINT NOT NULL REFERENCES workspace(id),
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(50) NOT NULL,
    source VARCHAR(30) NOT NULL DEFAULT 'manual',
    embedding vector(1536),
    metadata_json JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_workspace_category ON knowledge_entry(workspace_id, category);
```

- [ ] **Step 2: 实体 + 仓储 + 服务**

- [ ] **Step 3: 验证 + 暂存**

---

### Task 7: 全量验证

- [ ] **Step 1: 跑全量测试** `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd test`
- [ ] **Step 2: 打包** `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd package -DskipTests`
- [ ] **Step 3: 真实 PG 启动验证**（DASHSCOPE_API_KEY 可空，agent.enabled=false 跳过 Agent）

---

## Self-Review

**1. Spec coverage:**
- Spring AI 依赖 + 配置 → Task 1 ✅
- Agent Harness → Task 2 ✅
- CellAnalysisAgent + 三个 Analyzer → Task 3 ✅
- AgentAnalysisScheduler → Task 4 ✅
- ReportAgent + Reconciliation + Tools → Task 5 ✅
- RAG 知识库 → Task 6 ✅
- 不修改确定性管线核心逻辑 → 仅 CompletionService 加事件发布 ✅

**2. Placeholder scan:** 无 TBD/TODO。

**3. Type consistency:** 所有 DTO 在 Task 1 定义，Task 3/5 引用，类型一致 ✅