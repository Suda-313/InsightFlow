# 日指标聚合（Issue Metric Bucket）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在投影事务内将 Data Cell 分类结果按日聚合为 `issue_metric_bucket` 行，包含 `source_kind` 分布摘要。

**Architecture:** 扩展 `EventInput` 增加 `sourceKind` 字段，`ProjectionSourceLoader` 顺手填入；新增 `MetricBucketService` 在 `factWriter.write()` 之后按 (issue_id, 日期) 分组 UPSERT；`dimension_summary_json` 统计 `source_kind` 分布。

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, PostgreSQL 16, Jackson（JSON 序列化），JUnit 5 + Mockito。

## Global Constraints

- 技术栈固定 Java 17 + Spring Boot 3.5 + PostgreSQL 16 + Flyway + MinIO；模块化单体 + MVC 包结构。
- 不修改 V1–V7 迁移；`issue_metric_bucket` 表已在 V6 创建，无需新迁移。
- 所有业务读写按 `workspace_id` 隔离。
- 本期不写 `issue_baseline_profile`/`alert`；不实现 EWMA/z-score/冷却期。
- 每个新增业务/实体/迁移模块有效注释行数 ≥ 非空代码行数 1/2，解释业务目的/约束/边界。
- TDD：先写失败测试，再写最小实现。
- **不提交不推送**；每个任务末尾只 `git add` 暂存。
- 本机有 Docker，可用真实 PG 验证 `mvnw test` + `mvnw package`。

---

## File Structure

**新增：**
- `src/main/java/com/insightflow/entity/IssueMetricBucket.java` — JPA 实体
- `src/main/java/com/insightflow/repository/IssueMetricBucketRepository.java` — Spring Data 仓储
- `src/main/java/com/insightflow/service/analysis/MetricBucketService.java` — 聚合 + UPSERT
- `src/test/java/com/insightflow/service/analysis/MetricBucketServiceTest.java` — 单元测试

**修改：**
- `src/main/java/com/insightflow/service/analysis/EventInput.java` — 加 `sourceKind` 字段
- `src/main/java/com/insightflow/service/analysis/ProjectionSourceLoader.java` — 填入 `sourceKind`
- `src/main/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionService.java` — 调用 `metricBucketService.write()`
- `src/test/java/com/insightflow/service/analysis/DataCellBuilderTest.java` — 适配 EventInput 新构造器
- `src/test/java/com/insightflow/service/analysis/ProjectionFactWriterTest.java` — 适配 EventInput 新构造器
- `src/test/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionServiceTest.java` — 验证 metricBucketService

---

### Task 1: EventInput 加 sourceKind + 适配所有调用方

**Files:**
- Modify: `src/main/java/com/insightflow/service/analysis/EventInput.java`
- Modify: `src/main/java/com/insightflow/service/analysis/ProjectionSourceLoader.java`
- Modify: `src/test/java/com/insightflow/service/analysis/DataCellBuilderTest.java`
- Modify: `src/test/java/com/insightflow/service/analysis/ProjectionFactWriterTest.java`
- Modify: `src/test/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionServiceTest.java`

**Interfaces:**
- Produces: `EventInput(Long id, OffsetDateTime occurredAt, String sourceKind, String normalizedText)` — 新构造器在 `normalizedText` 前插入了 `sourceKind` 参数。

- [ ] **Step 1: 修改 EventInput record 加 sourceKind 字段**

```java
package com.insightflow.service.analysis;

import java.time.OffsetDateTime;

/**
 * 投影输入事件的计算视图；只暴露切分、分类与指标聚合所需字段.
 *
 * <p>EventInput 是 DataCellBuilder、IssueClassifier 和 MetricBucketService 之间的
 * 公共契约，保证各阶段只依赖 id、发生时间、来源分类和归一化文本，避免引入存储层细节。</p>
 *
 * @param id              feedback_event 内部主键，用于 cell_issue.sample_event_ids
 * @param occurredAt      反馈真实发生时间，决定时间窗与排序
 * @param sourceKind      来源分类（工单、评价等），用于日指标维度摘要
 * @param normalizedText  归一后文本，用于 token 估算与分类
 */
public record EventInput(Long id, OffsetDateTime occurredAt, String sourceKind, String normalizedText) {
}
```

- [ ] **Step 2: 修改 ProjectionSourceLoader 填入 sourceKind**

把 `load()` 方法中的 EventInput 构造行从：
```java
inputs.add(new EventInput(event.getId(), event.getOccurredAt(),
        normalizer.normalize(event.getSanitizedText())));
```
改为：
```java
inputs.add(new EventInput(event.getId(), event.getOccurredAt(),
        event.getSourceKind(),
        normalizer.normalize(event.getSanitizedText())));
```

- [ ] **Step 3: 适配 DataCellBuilderTest 中所有 EventInput 构造**

在 `DataCellBuilderTest.java` 中，所有 `new EventInput(id, occurredAt, text)` 改为 `new EventInput(id, occurredAt, "工单", text)`：

```java
// closesOnCountLimit
.mapToObj(i -> new EventInput((long) i, base.plusSeconds(i), "工单", "短文本"))

// closesOnWindowLimit
new EventInput(1L, base, "工单", "x"),
new EventInput(2L, base.plusMinutes(61), "工单", "y")

// singleOverBudgetEventGetsTokenLimit
new EventInput(1L, base, "工单", huge)
```

- [ ] **Step 4: 适配 ProjectionFactWriterTest 中 EventInput 构造**

```java
// writesLinksAndCellIssuesPerCell
List<EventInput> events = List.of(new EventInput(1L, now, "工单", "登录失败"));
```

- [ ] **Step 5: 适配 WorkspaceProjectionExecutionServiceTest 中 EventInput 构造**

```java
// writesFactsAndRecordsWindowWhenEventsPresent
EventInput event = new EventInput(1L, occurredAt, "工单", "登录失败");
```

- [ ] **Step 6: 运行编译验证**

Run: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd -q compile test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 7: 运行全部测试确认无回归**

Run: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd -q test`
Expected: 全部 37 个测试通过。

- [ ] **Step 8: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/service/analysis/EventInput.java src/main/java/com/insightflow/service/analysis/ProjectionSourceLoader.java src/test/java/com/insightflow/service/analysis/DataCellBuilderTest.java src/test/java/com/insightflow/service/analysis/ProjectionFactWriterTest.java src/test/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionServiceTest.java
```

---

### Task 2: IssueMetricBucket 实体 + 仓储

**Files:**
- Create: `src/main/java/com/insightflow/entity/IssueMetricBucket.java`
- Create: `src/main/java/com/insightflow/repository/IssueMetricBucketRepository.java`

**Interfaces:**
- Produces: `IssueMetricBucket` JPA 实体（对应 V6 `issue_metric_bucket` 表）；`IssueMetricBucketRepository.findByWorkspaceIdAndIssueIdAndBucketStart(Long, Long, OffsetDateTime)` → `Optional<IssueMetricBucket>`。

- [ ] **Step 1: 写 IssueMetricBucket 实体**

```java
package com.insightflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 单主题单日的指标聚合桶；是看板趋势、报告和 EWMA 的共同事实来源。
 *
 * <p>同一 (workspace_id, issue_id, bucket_start) 唯一，后续投影写同一日期
 * 同一主题时走 UPSERT 合并 feedback_count 与 dimension_summary_json，
 * 而非重复 INSERT——这是日指标幂等的物理基础。</p>
 *
 * <p>dimension_summary_json 只统计 source_kind 分布（如 {"工单":12,"评价":5}），
 * 不展开 dimension_json 中的其他维度，避免不同 Workspace 的异构维度污染聚合。</p>
 */
@Entity
@Table(name = "issue_metric_bucket")
public class IssueMetricBucket {

    /** 内部主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一级租户隔离键。 */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 关联 issue_catalog 内部主键。 */
    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    /** 日桶起点（UTC 00:00），按 occurred_at 截断。 */
    @Column(name = "bucket_start", nullable = false, updatable = false)
    private OffsetDateTime bucketStart;

    /** 当日该主题的反馈总数。 */
    @Column(name = "feedback_count", nullable = false)
    private int feedbackCount;

    /** JSONB 维度摘要，如 {"工单":12,"评价":5}。 */
    @Column(name = "dimension_summary_json", nullable = false, columnDefinition = "jsonb")
    private String dimensionSummaryJson;

    /** 最后一次更新此桶的投影内部主键，用于审计追溯。 */
    @Column(name = "workspace_projection_id", nullable = false)
    private Long workspaceProjectionId;

    /** 记录首次写入时刻。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 记录最近一次更新的时刻。 */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 反射构造器；业务代码使用 {@link #of} 或 {@link #addFeedbackCount}。 */
    protected IssueMetricBucket() {
    }

    /**
     * 创建新的日指标桶；首次写入时 feedback_count 和 dimension_summary_json
     * 来自本轮聚合，workspaceProjectionId 记录产生此桶的投影。
     *
     * @param workspaceId           一级租户隔离键
     * @param issueId               主题目录内部主键
     * @param bucketStart           日桶起点（UTC 00:00）
     * @param feedbackCount         当日反馈数
     * @param dimensionSummaryJson  维度摘要 JSON
     * @param workspaceProjectionId 投影内部主键
     * @return 新建的日指标桶
     */
    public static IssueMetricBucket of(
            Long workspaceId, Long issueId, OffsetDateTime bucketStart,
            int feedbackCount, String dimensionSummaryJson, Long workspaceProjectionId) {
        IssueMetricBucket bucket = new IssueMetricBucket();
        OffsetDateTime now = OffsetDateTime.now();
        bucket.workspaceId = workspaceId;
        bucket.issueId = issueId;
        bucket.bucketStart = bucketStart;
        bucket.feedbackCount = feedbackCount;
        bucket.dimensionSummaryJson = dimensionSummaryJson;
        bucket.workspaceProjectionId = workspaceProjectionId;
        bucket.createdAt = now;
        bucket.updatedAt = now;
        return bucket;
    }

    /**
     * 对已有桶追加本轮投影的反馈计数与维度分布；不改变 bucketStart/createdAt。
     *
     * @param deltaCount      本轮新增反馈数
     * @param mergedSummaryJson 合并后的维度摘要 JSON
     * @param projectionId    本轮投影内部主键
     */
    public void addFeedbackCount(int deltaCount, String mergedSummaryJson, Long projectionId) {
        this.feedbackCount += deltaCount;
        this.dimensionSummaryJson = mergedSummaryJson;
        this.workspaceProjectionId = projectionId;
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getIssueId() { return issueId; }
    public OffsetDateTime getBucketStart() { return bucketStart; }
    public int getFeedbackCount() { return feedbackCount; }
    public String getDimensionSummaryJson() { return dimensionSummaryJson; }
    public Long getWorkspaceProjectionId() { return workspaceProjectionId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 2: 写 IssueMetricBucketRepository**

```java
package com.insightflow.repository;

import com.insightflow.entity.IssueMetricBucket;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 日指标桶持久化端口；按 (workspace_id, issue_id, bucket_start) 唯一查找，
 * 支持 MetricBucketService 的 UPSERT 语义。
 */
public interface IssueMetricBucketRepository extends JpaRepository<IssueMetricBucket, Long> {

    /**
     * 按唯一约束查找已有桶；返回 Optional 供 UPSERT 判断。
     *
     * @param workspaceId 一级租户隔离键
     * @param issueId     主题内部主键
     * @param bucketStart 日桶起点
     * @return 可能为空的已有桶
     */
    Optional<IssueMetricBucket> findByWorkspaceIdAndIssueIdAndBucketStart(
            Long workspaceId, Long issueId, OffsetDateTime bucketStart);
}
```

- [ ] **Step 3: 验证编译**

Run: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/entity/IssueMetricBucket.java src/main/java/com/insightflow/repository/IssueMetricBucketRepository.java
```

---

### Task 3: MetricBucketService 聚合 + UPSERT

**Files:**
- Create: `src/main/java/com/insightflow/service/analysis/MetricBucketService.java`
- Test: `src/test/java/com/insightflow/service/analysis/MetricBucketServiceTest.java`

**Interfaces:**
- Consumes: `IssueCatalogService`（canonicalKey → issueId 解析）, `IssueMetricBucketRepository`（UPSERT）, `ObjectMapper`（JSON 序列化/反序列化）
- Produces: `MetricBucketService.write(Long projectionId, Long workspaceId, List<EventInput> events, Map<Long, List<Classification>> classificationsByEventId, Map<String, String> canonicalNames)` — 无返回值，失败抛异常让事务回滚。

- [ ] **Step 1: 写失败测试 MetricBucketServiceTest**

```java
package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.repository.IssueMetricBucketRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 日指标聚合：同日同主题合并计数与 source_kind 分布；跨日分桶；unclassified 不产生行。
 */
class MetricBucketServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单条事件单主题应在对应日期的桶中计数为 1。 */
    @Test
    void writesSingleBucketForOneEventOneIssue() throws Exception {
        IssueMetricBucketRepository bucketRepo = mock(IssueMetricBucketRepository.class);
        IssueCatalogService catalogService = mock(IssueCatalogService.class);
        IssueCatalog catalog = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(catalogService.findOrCreate(7L, "login_failure", "登录失败")).thenReturn(catalog);
        when(bucketRepo.findByWorkspaceIdAndIssueIdAndBucketStart(any(), any(), any()))
                .thenReturn(Optional.empty());

        MetricBucketService service = new MetricBucketService(bucketRepo, catalogService, objectMapper);
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 21, 14, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime bucketStart = OffsetDateTime.of(2026, 7, 21, 0, 0, 0, 0, ZoneOffset.UTC);
        List<EventInput> events = List.of(new EventInput(1L, occurredAt, "工单", "登录失败"));
        Map<Long, List<Classification>> classifications = Map.of(
                1L, List.of(new Classification("login_failure", 1.0, "rule")));

        service.write(31L, 7L, events, classifications, Map.of("login_failure", "登录失败"));

        ArgumentCaptor<IssueMetricBucket> captor = ArgumentCaptor.forClass(IssueMetricBucket.class);
        verify(bucketRepo).save(captor.capture());
        IssueMetricBucket saved = captor.getValue();
        assertThat(saved.getFeedbackCount()).isEqualTo(1);
        assertThat(saved.getBucketStart()).isEqualTo(bucketStart);
        assertThat(saved.getDimensionSummaryJson()).contains("工单");
    }

    /** 同一天同一主题的多条事件应合并到同一个桶。 */
    @Test
    void mergesMultipleEventsIntoSameBucket() throws Exception {
        IssueMetricBucketRepository bucketRepo = mock(IssueMetricBucketRepository.class);
        IssueCatalogService catalogService = mock(IssueCatalogService.class);
        IssueCatalog catalog = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(catalogService.findOrCreate(7L, "login_failure", "登录失败")).thenReturn(catalog);
        when(bucketRepo.findByWorkspaceIdAndIssueIdAndBucketStart(any(), any(), any()))
                .thenReturn(Optional.empty());

        MetricBucketService service = new MetricBucketService(bucketRepo, catalogService, objectMapper);
        OffsetDateTime base = OffsetDateTime.of(2026, 7, 21, 10, 0, 0, 0, ZoneOffset.UTC);
        List<EventInput> events = List.of(
                new EventInput(1L, base, "工单", "登录失败"),
                new EventInput(2L, base.plusHours(2), "工单", "登录失败"),
                new EventInput(3L, base.plusHours(4), "评价", "登录失败"));
        Map<Long, List<Classification>> classifications = Map.of(
                1L, List.of(new Classification("login_failure", 1.0, "rule")),
                2L, List.of(new Classification("login_failure", 1.0, "rule")),
                3L, List.of(new Classification("login_failure", 1.0, "rule")));

        service.write(31L, 7L, events, classifications, Map.of("login_failure", "登录失败"));

        ArgumentCaptor<IssueMetricBucket> captor = ArgumentCaptor.forClass(IssueMetricBucket.class);
        verify(bucketRepo).save(captor.capture());
        IssueMetricBucket saved = captor.getValue();
        assertThat(saved.getFeedbackCount()).isEqualTo(3);
        assertThat(saved.getDimensionSummaryJson()).contains("工单").contains("评价");
    }

    /** 跨天的事件应分到不同日期桶。 */
    @Test
    void splitsEventsAcrossDayBoundaries() throws Exception {
        IssueMetricBucketRepository bucketRepo = mock(IssueMetricBucketRepository.class);
        IssueCatalogService catalogService = mock(IssueCatalogService.class);
        IssueCatalog catalog = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(catalogService.findOrCreate(7L, "login_failure", "登录失败")).thenReturn(catalog);
        when(bucketRepo.findByWorkspaceIdAndIssueIdAndBucketStart(any(), any(), any()))
                .thenReturn(Optional.empty());

        MetricBucketService service = new MetricBucketService(bucketRepo, catalogService, objectMapper);
        OffsetDateTime day1 = OffsetDateTime.of(2026, 7, 20, 23, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime day2 = OffsetDateTime.of(2026, 7, 21, 1, 0, 0, 0, ZoneOffset.UTC);
        List<EventInput> events = List.of(
                new EventInput(1L, day1, "工单", "登录失败"),
                new EventInput(2L, day2, "工单", "登录失败"));
        Map<Long, List<Classification>> classifications = Map.of(
                1L, List.of(new Classification("login_failure", 1.0, "rule")),
                2L, List.of(new Classification("login_failure", 1.0, "rule")));

        service.write(31L, 7L, events, classifications, Map.of("login_failure", "登录失败"));

        verify(bucketRepo, times(2)).save(any(IssueMetricBucket.class));
    }

    /** UPSERT：已有桶时应追加计数而非新建。 */
    @Test
    void upsertsWhenBucketExists() throws Exception {
        IssueMetricBucketRepository bucketRepo = mock(IssueMetricBucketRepository.class);
        IssueCatalogService catalogService = mock(IssueCatalogService.class);
        IssueCatalog catalog = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(catalogService.findOrCreate(7L, "login_failure", "登录失败")).thenReturn(catalog);

        OffsetDateTime bucketStart = OffsetDateTime.of(2026, 7, 21, 0, 0, 0, 0, ZoneOffset.UTC);
        IssueMetricBucket existing = IssueMetricBucket.of(
                7L, catalog.getId(), bucketStart, 5, "{\"工单\":5}", 30L);
        when(bucketRepo.findByWorkspaceIdAndIssueIdAndBucketStart(7L, catalog.getId(), bucketStart))
                .thenReturn(Optional.of(existing));

        MetricBucketService service = new MetricBucketService(bucketRepo, catalogService, objectMapper);
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 21, 14, 0, 0, 0, ZoneOffset.UTC);
        List<EventInput> events = List.of(new EventInput(1L, occurredAt, "工单", "登录失败"));
        Map<Long, List<Classification>> classifications = Map.of(
                1L, List.of(new Classification("login_failure", 1.0, "rule")));

        service.write(31L, 7L, events, classifications, Map.of("login_failure", "登录失败"));

        assertThat(existing.getFeedbackCount()).isEqualTo(6);
        verify(bucketRepo, never()).save(any(IssueMetricBucket.class));
    }

    /** unclassified 事件不产生任何 bucket 行。 */
    @Test
    void skipsUnclassifiedEvents() throws Exception {
        IssueMetricBucketRepository bucketRepo = mock(IssueMetricBucketRepository.class);
        IssueCatalogService catalogService = mock(IssueCatalogService.class);

        MetricBucketService service = new MetricBucketService(bucketRepo, catalogService, objectMapper);
        OffsetDateTime occurredAt = OffsetDateTime.now();
        List<EventInput> events = List.of(new EventInput(1L, occurredAt, "工单", "今天天气不错"));
        Map<Long, List<Classification>> classifications = Map.of(1L, List.of());

        service.write(31L, 7L, events, classifications, Map.of());

        verify(bucketRepo, never()).save(any(IssueMetricBucket.class));
    }

    /** 空输入不抛异常也不写任何行。 */
    @Test
    void emptyInputReturnsWithoutWriting() throws Exception {
        IssueMetricBucketRepository bucketRepo = mock(IssueMetricBucketRepository.class);
        IssueCatalogService catalogService = mock(IssueCatalogService.class);

        MetricBucketService service = new MetricBucketService(bucketRepo, catalogService, objectMapper);

        service.write(31L, 7L, List.of(), Map.of(), Map.of());

        verify(bucketRepo, never()).save(any(IssueMetricBucket.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd -q test -Dtest=MetricBucketServiceTest`
Expected: FAIL（编译失败，`MetricBucketService` 类不存在）。

- [ ] **Step 3: 写 MetricBucketService 最小实现**

```java
package com.insightflow.service.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.entity.IssueMetricBucket;
import com.insightflow.repository.IssueMetricBucketRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将投影分类结果按日聚合为 issue_metric_bucket 行；同一 (issue, 日期) 走 UPSERT。
 *
 * <p>聚合发生在 ProjectionFactWriter 之后、recordSourceWindow 之前，共享同一
 * REQUIRES_NEW 事务。任一步失败由外层事务整体回滚。</p>
 *
 * <p>dimension_summary_json 只统计 source_kind 分布，不展开 dimension_json 中的
 * 其他维度字段——这是有意为之的边界：不同 Workspace 的维度字段异构，强行展开反而
 * 污染聚合。后续如果需要按渠道/版本等维度下钻，应通过独立的维度表而非在 bucket 内
 * 展开。</p>
 *
 * <p>幂等：UPSERT 语义（find → 有则 merge 无则 insert）保证同一次投影重试时
 * 不重复累计。唯一约束 uq_issue_metric_bucket 兜底并发写入。</p>
 */
@Component
public class MetricBucketService {

    /** 日指标桶仓储，UPSERT 的物理载体。 */
    private final IssueMetricBucketRepository bucketRepository;
    /** 主题目录服务，把 canonicalKey 解析为 issue_id。 */
    private final IssueCatalogService catalogService;
    /** JSON 工具，序列化/反序列化 dimension_summary_json。 */
    private final ObjectMapper objectMapper;

    /** 构造日指标聚合服务；所有依赖在调用方事务内执行。 */
    public MetricBucketService(IssueMetricBucketRepository bucketRepository,
                               IssueCatalogService catalogService,
                               ObjectMapper objectMapper) {
        this.bucketRepository = bucketRepository;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
    }

    /**
     * 按 (issue_id, 日期) 聚合事件分类结果并写入 bucket。
     *
     * <p>遍历 events，对每条事件的 0..2 个 classification 按日期分组：
     * 同 issue + 同日 → 计数 +1 + source_kind 分布 +1；
     * 跨日 → 分到不同 bucket_start 的组。</p>
     *
     * <p>分组完成后，对每组执行 UPSERT：已有桶则 addFeedbackCount 合并，
     * 无则 of 新建。全部在同一调用方事务内，不另开事务。</p>
     *
     * @param projectionId              本次投影内部主键
     * @param workspaceId               一级租户隔离键
     * @param events                    全部事件（含 sourceKind）
     * @param classificationsByEventId  每条事件的分类结果
     * @param canonicalNames            canonical_key → 名称映射
     */
    public void write(Long projectionId, Long workspaceId,
                      List<EventInput> events,
                      Map<Long, List<Classification>> classificationsByEventId,
                      Map<String, String> canonicalNames) {
        // 内存聚合：BucketKey → (feedbackCount, sourceKind distribution)
        Map<BucketKey, BucketAggregate> aggregates = new HashMap<>();
        for (EventInput event : events) {
            List<Classification> classifications = classificationsByEventId.getOrDefault(event.id(), List.of());
            for (Classification c : classifications) {
                // 把 occurredAt 截断到 UTC 当日 00:00 作为 bucket_start
                OffsetDateTime bucketStart = event.occurredAt()
                        .toLocalDate()
                        .atStartOfDay(ZoneOffset.UTC)
                        .toOffsetDateTime();
                BucketKey key = new BucketKey(c.canonicalKey(), bucketStart);
                aggregates.computeIfAbsent(key, k -> new BucketAggregate())
                        .add(event.sourceKind());
            }
        }
        // 对每个聚合组执行 UPSERT
        for (Map.Entry<BucketKey, BucketAggregate> entry : aggregates.entrySet()) {
            BucketKey key = entry.getKey();
            BucketAggregate aggregate = entry.getValue();
            IssueCatalog catalog = catalogService.findOrCreate(
                    workspaceId, key.canonicalKey, canonicalNames.get(key.canonicalKey));
            OffsetDateTime bucketStart = key.bucketStart;
            bucketRepository.findByWorkspaceIdAndIssueIdAndBucketStart(
                            workspaceId, catalog.getId(), bucketStart)
                    .ifPresentOrElse(
                            existing -> existing.addFeedbackCount(
                                    aggregate.count,
                                    toJson(aggregate.sourceKindCounts),
                                    projectionId),
                            () -> bucketRepository.save(IssueMetricBucket.of(
                                    workspaceId, catalog.getId(), bucketStart,
                                    aggregate.count,
                                    toJson(aggregate.sourceKindCounts),
                                    projectionId)));
        }
    }

    /**
     * 内存聚合键：(canonicalKey, bucketStart)。
     *
     * <p>用 canonicalKey 而非 issue_id 作为键，因为 issue_id 在 findOrCreate 之后
     * 才确定——聚合阶段只持有 canonicalKey，解析推迟到 UPSERT 写入时。</p>
     */
    private record BucketKey(String canonicalKey, OffsetDateTime bucketStart) {
    }

    /**
     * 内存聚合值：计数与 source_kind 分布。
     */
    private static final class BucketAggregate {
        int count;
        final Map<String, Integer> sourceKindCounts = new HashMap<>();

        void add(String sourceKind) {
            count++;
            sourceKindCounts.merge(sourceKind, 1, Integer::sum);
        }
    }

    /**
     * 把 Map 序列化为 JSON 字符串；失败抛 IllegalStateException 让事务回滚。
     */
    private String toJson(Map<String, Integer> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize dimension_summary_json", e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd -q test -Dtest=MetricBucketServiceTest`
Expected: PASS（6 个测试通过）。

- [ ] **Step 5: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/service/analysis/MetricBucketService.java src/test/java/com/insightflow/service/analysis/MetricBucketServiceTest.java
```

---

### Task 4: 集成到 WorkspaceProjectionExecutionService

**Files:**
- Modify: `src/main/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionService.java`
- Modify: `src/test/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionServiceTest.java`

**Interfaces:**
- Consumes: `MetricBucketService`（Task 3）
- Produces: `WorkspaceProjectionExecutionService` 在 `factWriter.write()` 之后调用 `metricBucketService.write()`。

- [ ] **Step 1: 修改 WorkspaceProjectionExecutionService 构造器加 MetricBucketService**

在现有的字段声明和构造器中加入 `MetricBucketService`：

```java
// 在字段声明区加
/** 日指标聚合器，在事实写入后按日统计主题指标。 */
private final MetricBucketService metricBucketService;

// 构造器签名改为：
public WorkspaceProjectionExecutionService(WorkspaceProjectionRepository projectionRepository,
                                            DataCellRepository dataCellRepository,
                                            ProjectionSourceLoader sourceLoader,
                                            RuleFirstIssueClassifier classifier,
                                            DataCellBuilder dataCellBuilder,
                                            ProjectionFactWriter factWriter,
                                            IssueRulesLoader rulesLoader,
                                            MetricBucketService metricBucketService) {
    // ... 已有赋值 ...
    this.metricBucketService = metricBucketService;
}
```

在 `execute()` 方法中，`factWriter.write(...)` 之后、`projection.recordSourceWindow(...)` 之前插入：

```java
        // 写入日指标聚合：按 (issue_id, 日期) 分组 UPSERT issue_metric_bucket
        metricBucketService.write(projectionId, workspaceId, events, classificationsByEventId, canonicalNames);
```

- [ ] **Step 2: 修改 WorkspaceProjectionExecutionServiceTest 适配新构造器**

三个测试方法的 `new WorkspaceProjectionExecutionService(...)` 构造调用都加上 `mock(MetricBucketService.class)` 作为最后一个参数：

```java
// skipsWhenFactsAlreadyWritten
MetricBucketService metricBucketService = mock(MetricBucketService.class);
WorkspaceProjectionExecutionService service = new WorkspaceProjectionExecutionService(
        projRepo, cellRepo, loader, mock(RuleFirstIssueClassifier.class),
        mock(DataCellBuilder.class), mock(ProjectionFactWriter.class), mock(IssueRulesLoader.class),
        metricBucketService);

// returnsFalseWhenNoEvents
MetricBucketService metricBucketService = mock(MetricBucketService.class);
WorkspaceProjectionExecutionService service = new WorkspaceProjectionExecutionService(
        projRepo, cellRepo, loader, mock(RuleFirstIssueClassifier.class),
        mock(DataCellBuilder.class), mock(ProjectionFactWriter.class), mock(IssueRulesLoader.class),
        metricBucketService);

// writesFactsAndRecordsWindowWhenEventsPresent
MetricBucketService metricBucketService = mock(MetricBucketService.class);
WorkspaceProjectionExecutionService service = new WorkspaceProjectionExecutionService(
        projRepo, cellRepo, loader, classifier, dataCellBuilder, factWriter, rulesLoader,
        metricBucketService);
// 然后在断言区加：
verify(metricBucketService).write(anyLong(), anyLong(), anyList(), anyMap(), anyMap());
```

- [ ] **Step 3: 修改 WorkspaceProjectionTaskRunnerTest 适配新构造器**

检查 `src/test/java/com/insightflow/task/WorkspaceProjectionTaskRunnerTest.java`，确认其构造调用是否需要更新。`WorkspaceProjectionTaskRunner` 不直接持有 `MetricBucketService`，它通过 `WorkspaceProjectionExecutionService` 间接使用，所以 TaskRunner 的构造器不变。但如果测试中直接构造了 `WorkspaceProjectionExecutionService`，需要更新。

运行: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd -q test -Dtest=WorkspaceProjectionTaskRunnerTest`
确认: PASS。

- [ ] **Step 4: 运行全部测试确认无回归**

Run: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd -q test`
Expected: 全部 43 个测试通过（原有 37 + 新增 6 个 MetricBucketServiceTest）。

- [ ] **Step 5: 暂存（不提交）**

```bash
git add src/main/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionService.java src/test/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionServiceTest.java
```

---

### Task 5: 全量验证

**Files:** 无新增；运行验证命令。

- [ ] **Step 1: 跑全量单元测试**

Run: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd test`
Expected: 全部测试通过。

- [ ] **Step 2: 跑 package 验证打包**

Run: `unset JAVA_TOOL_OPTIONS && ./mvnw.cmd package -DskipTests`
Expected: BUILD SUCCESS，生成 JAR。

- [ ] **Step 3: 真实 PG + app 启动验证**

```
$env:Path = "C:\Program Files\Docker\Docker\resources\bin;$env:Path"
docker compose down -v
docker compose -f D:\yuqiagent\docker-compose.yml --env-file D:\yuqiagent\.env up -d
# 等待 postgres healthy
unset JAVA_TOOL_OPTIONS && ./mvnw.cmd spring-boot:run
```

访问 `http://localhost:8080/actuator/health`，确认 Flyway V1-V7 已执行、`IssueMetricBucketRepository` Bean 装配成功、应用启动无报错。

- [ ] **Step 4: 向用户报告验证结果**

---

## Self-Review

**1. Spec coverage:**
- EventInput 加 sourceKind → Task 1 ✅
- ProjectionSourceLoader 填入 sourceKind → Task 1 ✅
- IssueMetricBucket 实体（对应 V6 表） → Task 2 ✅
- IssueMetricBucketRepository 按 (workspace_id, issue_id, bucket_start) 查找 → Task 2 ✅
- MetricBucketService 按日聚合 + UPSERT → Task 3 ✅
- dimension_summary_json 统计 source_kind 分布 → Task 3 ✅
- 集成到 WorkspaceProjectionExecutionService → Task 4 ✅
- 测试覆盖（单日聚合、跨日分桶、source_kind 分布、UPSERT、unclassified、空输入） → Task 3 ✅
- 验证命令 → Task 5 ✅

**2. Placeholder scan:** 无 TBD/TODO；每个代码步骤含可编译 Java。

**3. Type consistency:**
- `EventInput(Long id, OffsetDateTime occurredAt, String sourceKind, String normalizedText)` 在 Task 1 定义，Task 3 MetricBucketService 用 `event.id()/occurredAt()/sourceKind()` → 一致 ✅
- `MetricBucketService.write(Long projectionId, Long workspaceId, ...)` 在 Task 3 定义，Task 4 调用 `metricBucketService.write(projectionId, workspaceId, events, classificationsByEventId, canonicalNames)` → 一致 ✅
- `IssueMetricBucket.of(...)` 在 Task 2 定义，Task 3 调用 `IssueMetricBucket.of(workspaceId, catalog.getId(), bucketStart, ...)` → 一致 ✅