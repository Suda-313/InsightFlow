# Dashboard Projection and On-Demand Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically project each successful CSV import into the Workspace dashboard and alerts, while letting users create repeatable, read-only analysis reports over already projected data.

**Architecture:** Keep the modular Spring Boot monolith and use PostgreSQL plus the existing `AsyncTask` lease lifecycle as the durable coordinator. An import completion creates an idempotent projection command; a projection runner serializes per Workspace and owns all writes to issue metrics, EWMA profiles and alerts. A report runner only reads the already committed projection facts, so report regeneration cannot mutate dashboard data.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, PostgreSQL 16, Flyway, Jackson, Apache Commons CSV, MinIO, JUnit 5.

## Global Constraints

- Do not add Kafka, Redis queues, a second worker process, a microservice, crawler, multi-Agent loop, or real Qwen request.
- Add only forward Flyway migrations; do not edit migrations V1--V5.
- Keep all business reads and writes explicitly scoped by `workspace_id`.
- Keep raw CSV in MinIO and allow only already-sanitized `FeedbackEvent` text into rules, evidence and reports.
- Use `timestamptz` for timestamps and identity `BIGINT` for internal primary keys; retain UUIDv7 only for public API identifiers.
- Keep meaningful comments in business, persistence and async-task modules at no less than one comment line per two code lines.
- A normal “regenerate report” must never write `issue_metric_bucket`, `issue_baseline_profile`, `alert`, `feedback_issue_link`, or an import file projection state.
- Do not commit or push as part of this plan unless the user explicitly asks after reviewing the implementation.

---

## File structure and responsibilities

| Path | Responsibility |
|---|---|
| `src/main/resources/db/migration/V6__add_dashboard_projection_schema.sql` | Forward schema for projections, topics, metrics, profiles, alerts and reports. |
| `src/main/java/com/insightflow/entity/*` | JPA state machines for automatic projection and read-only report snapshots. |
| `src/main/java/com/insightflow/repository/*` | Workspace-scoped queries, locks and idempotency lookups. |
| `src/main/java/com/insightflow/service/analysis/*` | Pure classification, cell construction, EWMA calculation, metric and report assembly. |
| `src/main/java/com/insightflow/task/*Projection*` | Command creation, lease-aware scheduling and execution of automatic projection tasks. |
| `src/main/java/com/insightflow/task/*Report*` | Command creation and execution of read-only report tasks. |
| `src/main/java/com/insightflow/controller/*` | Dashboard and report HTTP contracts only; no repositories or formulas. |
| `src/test/java/com/insightflow/service/analysis/*` | Rule, cell, EWMA and read-only-report unit tests. |
| `src/test/java/com/insightflow/task/*` | Projection command and task-runner integration tests using PostgreSQL Testcontainers. |

### Task 1: Establish the durable schema and state boundaries

**Files:**
- Create: `src/main/resources/db/migration/V6__add_dashboard_projection_schema.sql`
- Modify: `src/main/java/com/insightflow/entity/ImportFile.java`
- Modify: `src/main/java/com/insightflow/entity/AsyncTask.java`
- Create: `src/main/java/com/insightflow/entity/WorkspaceProjection.java`
- Create: `src/main/java/com/insightflow/entity/ProjectionFile.java`
- Create: `src/main/java/com/insightflow/entity/AnalysisReport.java`
- Create: `src/main/java/com/insightflow/entity/AnalysisReportFile.java`
- Test: `src/test/java/com/insightflow/task/ProjectionSchemaIntegrationTest.java`

**Interfaces:**
- `ImportFile.markProjectionPending()`, `markProjecting()`, `markProjected()` and `markRebuildRequired()` own the file projection lifecycle.
- `AsyncTask.queuedProjection(Long workspaceId, String idempotencyKey, String payloadJson)` and `AsyncTask.queuedReport(...)` create durable task types without an `import_file_id`.
- `WorkspaceProjection.queued(Long workspaceId, Long asyncTaskId, String ruleVersion)` and `AnalysisReport.queued(Long workspaceId, Long asyncTaskId, String reportVersion, OffsetDateTime sourceSnapshotAt)` are the respective user-internal records.

- [ ] **Step 1: Write the failing migration integration test**

```java
@Test
void flywayCreatesProjectionAndReportTables() {
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables "
                    + "where table_name in ('workspace_projection', 'analysis_report', 'issue_catalog', 'alert')",
            Integer.class)).isEqualTo(4);
}
```

- [ ] **Step 2: Run the test to verify the tables do not yet exist**

Run: `D:\insightflow\mvnw.cmd -Dtest=ProjectionSchemaIntegrationTest test`

Expected: FAIL because Flyway has not created `workspace_projection`.

- [ ] **Step 3: Add V6 as a forward-only migration**

```sql
ALTER TABLE import_file
    ADD COLUMN projection_status VARCHAR(30) NOT NULL DEFAULT 'pending';

CREATE TABLE workspace_projection (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    workspace_id BIGINT NOT NULL REFERENCES workspace(id),
    async_task_id BIGINT NOT NULL UNIQUE REFERENCES async_task(id),
    status VARCHAR(30) NOT NULL,
    rule_version VARCHAR(80) NOT NULL,
    source_window_start TIMESTAMPTZ,
    source_window_end TIMESTAMPTZ,
    baseline_snapshot_at TIMESTAMPTZ,
    projected_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analysis_report (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    workspace_id BIGINT NOT NULL REFERENCES workspace(id),
    async_task_id BIGINT NOT NULL UNIQUE REFERENCES async_task(id),
    status VARCHAR(30) NOT NULL,
    report_version VARCHAR(80) NOT NULL,
    source_snapshot_at TIMESTAMPTZ NOT NULL,
    scope_json JSONB NOT NULL,
    report_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Add `projection_file`, `analysis_report_file`, `issue_catalog`, `issue_alias`, `feedback_issue_link`, `data_cell`, `cell_issue`, `issue_metric_bucket`, `issue_baseline_profile`, and `alert` with `workspace_id` foreign keys. `issue_catalog` has its own UUIDv7 `public_id`; add unique constraints for `(workspace_id, canonical_key)`, `(workspace_projection_id, import_file_id)`, `(analysis_report_id, import_file_id)`, and `(workspace_id, issue_id, bucket_start)`.

- [ ] **Step 4: Add state transitions with no public setter**

```java
public void markProjectionPending() {
    this.projectionStatus = "pending";
    this.updatedAt = OffsetDateTime.now();
}

public void markProjected() {
    this.projectionStatus = "projected";
    this.updatedAt = OffsetDateTime.now();
}
```

Make `AsyncTask.queuedProjection` and `queuedReport` set `taskType` to `projection` and `report` respectively, preserve the existing max-attempt and lease behavior, and leave `importFileId` null for these task types.

- [ ] **Step 5: Run the migration test and the existing suite**

Run: `D:\insightflow\mvnw.cmd -Dtest=ProjectionSchemaIntegrationTest test` then `D:\insightflow\mvnw.cmd test`

Expected: both commands exit `0`.

### Task 2: Build deterministic topic, cell and EWMA primitives

**Files:**
- Create: `src/main/resources/config/analysis/issue-rules.toml`
- Create: `src/main/java/com/insightflow/service/analysis/IssueClassifier.java`
- Create: `src/main/java/com/insightflow/service/analysis/RuleFirstIssueClassifier.java`
- Create: `src/main/java/com/insightflow/service/analysis/DataCellBuilder.java`
- Create: `src/main/java/com/insightflow/service/analysis/EwmaBaselineCalculator.java`
- Create: `src/main/java/com/insightflow/service/analysis/ProjectionSettings.java`
- Test: `src/test/java/com/insightflow/service/analysis/RuleFirstIssueClassifierTest.java`
- Test: `src/test/java/com/insightflow/service/analysis/DataCellBuilderTest.java`
- Test: `src/test/java/com/insightflow/service/analysis/EwmaBaselineCalculatorTest.java`

**Interfaces:**
- `IssueClassifier.classify(String normalizedText)` returns `IssueMatch(canonicalKey, canonicalName, assignmentMethod, confidence)` or `IssueMatch.unclassified()`.
- `DataCellBuilder.build(List<FeedbackInput>)` returns immutable `List<DataCellInput>` with at most 40 events, 60 minutes or 6000 estimated tokens per cell.
- `EwmaBaselineCalculator.evaluate(BaselineState baseline, long currentCount)` returns `BaselineDecision` before it returns `nextState`.

- [ ] **Step 1: Write failing rule-classification tests**

```java
@Test
void picksTheHigherPriorityRuleAndNeverCreatesAnIssue() {
    IssueMatch match = classifier.classify("login failed after update");
    assertThat(match.canonicalKey()).isEqualTo("login_failure");
    assertThat(match.assignmentMethod()).isEqualTo("rule");
}

@Test
void returnsUnclassifiedWhenNoRuleMatches() {
    assertThat(classifier.classify("ordinary feedback").assignmentMethod())
            .isEqualTo("unclassified");
}
```

- [ ] **Step 2: Add versioned rules and a pure classifier**

```toml
[[issue]]
canonical_key = "login_failure"
canonical_name = "登录失败"
priority = 100
any_patterns = ["login failed", "登录失败", "无法登录"]
exclude_patterns = ["登录成功"]
```

```java
public IssueMatch classify(String normalizedText) {
    return rules.stream()
            .filter(rule -> rule.matches(normalizedText))
            .sorted(Comparator.comparingInt(IssueRule::priority).reversed()
                    .thenComparingInt(rule -> rule.matchedPatternCount(normalizedText)).reversed()
                    .thenComparing(IssueRule::canonicalKey))
            .findFirst()
            .map(rule -> IssueMatch.rule(rule.canonicalKey(), rule.canonicalName()))
            .orElseGet(IssueMatch::unclassified);
}
```

- [ ] **Step 3: Write failing cell-boundary and EWMA tests**

```java
@Test
void closesACellAtFortyEvents() {
    assertThat(builder.build(fortyOneEvents()).get(0).events()).hasSize(40);
}

@Test
void evaluatesAgainstOldBaselineBeforeUpdatingIt() {
    BaselineDecision decision = calculator.evaluate(new BaselineState(7, 2.0, 1.0), 8);
    assertThat(decision.zScore()).isEqualTo(6.0);
    assertThat(decision.nextState().ewma()).isEqualTo(3.8);
}
```

- [ ] **Step 4: Implement bounded cells and baseline calculation**

```java
public BaselineDecision evaluate(BaselineState baseline, long currentCount) {
    double sigma = Math.sqrt(Math.max(baseline.variance(), 0D));
    double zScore = (currentCount - baseline.ewma()) / Math.max(sigma, 1D);
    double nextEwma = alpha * currentCount + (1D - alpha) * baseline.ewma();
    double nextVariance = alpha * Math.pow(currentCount - baseline.ewma(), 2D)
            + (1D - alpha) * baseline.variance();
    return new BaselineDecision(zScore, new BaselineState(
            baseline.activeBuckets() + 1, nextEwma, nextVariance));
}
```

Implement token estimation as `max(1, sanitizedText.length() / 4)`, append events in `occurredAt` order, and close before an event that would exceed any configured limit.

- [ ] **Step 5: Run the three pure-service tests**

Run: `D:\insightflow\mvnw.cmd -Dtest=RuleFirstIssueClassifierTest,DataCellBuilderTest,EwmaBaselineCalculatorTest test`

Expected: exit code `0`; no model call or database is needed.

### Task 3: Create idempotent automatic projection commands after import completion

**Files:**
- Create: `src/main/java/com/insightflow/dto/analysis/ProjectionTaskPayload.java`
- Create: `src/main/java/com/insightflow/task/WorkspaceProjectionCommandService.java`
- Modify: `src/main/java/com/insightflow/task/ImportTaskCompletionService.java`
- Modify: `src/main/java/com/insightflow/repository/ImportFileRepository.java`
- Modify: `src/main/java/com/insightflow/repository/AsyncTaskRepository.java`
- Create: `src/main/java/com/insightflow/repository/WorkspaceProjectionRepository.java`
- Test: `src/test/java/com/insightflow/task/WorkspaceProjectionCommandServiceTest.java`

**Interfaces:**
- `WorkspaceProjectionCommandService.enqueueForImportedFile(Long workspaceId, Long importFileId)` marks a processed file pending and returns one durable projection task.
- `ProjectionTaskPayload(List<UUID> fileIds, String ruleVersion)` is immutable JSON stored in `AsyncTask.payloadJson`.
- `ImportTaskCompletionService.complete(...)` invokes `enqueueForImportedFile` only after the import task's successful transaction commits.

- [ ] **Step 1: Write the failing command-service test**

```java
@Test
void createsOnlyOneProjectionTaskForOneProcessedFile() {
    AsyncTask first = commandService.enqueueForImportedFile(workspaceId, fileId);
    AsyncTask second = commandService.enqueueForImportedFile(workspaceId, fileId);
    assertThat(second.getPublicId()).isEqualTo(first.getPublicId());
}
```

- [ ] **Step 2: Implement the idempotent command transaction**

```java
@Transactional
public AsyncTask enqueueForImportedFile(Long workspaceId, Long importFileId) {
    ImportFile file = importFileRepository.findByIdAndWorkspaceIdForUpdate(importFileId, workspaceId)
            .orElseThrow(() -> new ImportValidationException("导入文件不存在或不属于当前工作区。"));
    if (!"processed".equals(file.getStatus())) {
        throw new ImportValidationException("只有成功导入的文件可以进入看板投影。");
    }
    AsyncTask existing = taskRepository.findFirstByWorkspaceIdAndImportFileIdAndTaskTypeOrderByCreatedAtDesc(
            workspaceId, importFileId, "projection").orElse(null);
    if (existing != null) return existing;
    file.markProjectionPending();
    return taskRepository.save(AsyncTask.queuedProjection(
            workspaceId, "projection:file:" + importFileId, writePayload(file)));
}
```

- [ ] **Step 3: Publish only after a completed import**

```java
if (result.failedRows() == 0 || result.importedRows() > 0) {
    transactionSynchronization.afterCommit(() ->
            projectionCommandService.enqueueForImportedFile(task.getWorkspaceId(), file.getId()));
}
```

Call the command only after `ImportTaskCompletionService` has persisted the final import status and `ImportFile.markProcessed()`; never enqueue for a task-level import failure.

- [ ] **Step 4: Run the command test and regression suite**

Run: `D:\insightflow\mvnw.cmd -Dtest=WorkspaceProjectionCommandServiceTest test` then `D:\insightflow\mvnw.cmd test`

Expected: both commands exit `0`; duplicate import completion still yields one projection task.

### Task 4: Execute projection serially and commit dashboard facts atomically

**Files:**
- Create: `src/main/java/com/insightflow/task/WorkspaceProjectionLeaseService.java`
- Create: `src/main/java/com/insightflow/task/WorkspaceProjectionScheduler.java`
- Create: `src/main/java/com/insightflow/task/WorkspaceProjectionTaskRunner.java`
- Create: `src/main/java/com/insightflow/service/analysis/IssueCatalogService.java`
- Create: `src/main/java/com/insightflow/service/analysis/MetricBucketService.java`
- Create: `src/main/java/com/insightflow/service/analysis/AlertDetector.java`
- Create: `src/main/java/com/insightflow/repository/IssueCatalogRepository.java`
- Create: `src/main/java/com/insightflow/repository/IssueMetricBucketRepository.java`
- Create: `src/main/java/com/insightflow/repository/IssueBaselineProfileRepository.java`
- Create: `src/main/java/com/insightflow/repository/AlertRepository.java`
- Modify: `src/main/java/com/insightflow/repository/FeedbackEventRepository.java`
- Modify: `src/main/java/com/insightflow/config/ImportTaskConfiguration.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/insightflow/task/WorkspaceProjectionTaskRunnerIntegrationTest.java`

**Interfaces:**
- `WorkspaceProjectionTaskRunner.run(UUID taskPublicId, String workerId)` uses the existing task lease contract and processes only task type `projection`.
- `MetricBucketService.record(...)` returns per-topic daily counts and writes each bucket exactly once for `(workspace, issue, day)`.
- `AlertDetector.detect(BaselineState baseline, long currentCount, OffsetDateTime bucketStart)` returns `Optional<AlertCandidate>` before the baseline is saved.

- [ ] **Step 1: Write the failing end-to-end projection test**

```java
@Test
void successfulProjectionBuildsDashboardFactsWithoutCreatingAReport() {
    runProjectionFor(workspaceWithSevenHistoricalBucketsAndOneSpike());
    assertThat(metricBucketRepository.count()).isGreaterThan(0);
    assertThat(alertRepository.findAll()).hasSize(1);
    assertThat(analysisReportRepository.count()).isZero();
}
```

- [ ] **Step 2: Configure a bounded executor and dispatch loop**

```yaml
insightflow:
  projection:
    dispatch-delay-ms: ${PROJECTION_DISPATCH_DELAY_MS:5000}
    lease-seconds: ${PROJECTION_TASK_LEASE_SECONDS:120}
    max-dispatch-per-cycle: ${PROJECTION_MAX_DISPATCH_PER_CYCLE:2}
    alpha: ${PROJECTION_EWMA_ALPHA:0.3}
    z-threshold: ${PROJECTION_Z_THRESHOLD:2.0}
    global-floor: ${PROJECTION_GLOBAL_FLOOR:5}
    min-history-buckets: ${PROJECTION_MIN_HISTORY_BUCKETS:7}
    alert-cooldown-hours: ${PROJECTION_ALERT_COOLDOWN_HOURS:6}
```

Use a dedicated `projectionTaskExecutor`; the scheduler claims only `task_type='projection'`. Before claiming, reject a second running projection in the same Workspace by selecting its `workspace_projection` row with a pessimistic lock.

- [ ] **Step 3: Implement atomic projection ordering**

```java
@Transactional
void project(AsyncTask task, WorkspaceProjection projection) {
    List<Long> importFileIds = projectionFileRepository.findImportFileIdsByProjectionId(projection.getId());
    List<FeedbackEvent> events = feedbackEventRepository
            .findActiveByWorkspaceIdAndImportFileIds(task.getWorkspaceId(), importFileIds);
    if (containsLateEvent(events, baselineProfile.getLastProcessedBucket())) {
        projection.markRebuildRequired();
        importFiles.forEach(ImportFile::markRebuildRequired);
        return;
    }
    List<DataCellInput> cells = dataCellBuilder.build(toInputs(events));
    List<BucketCount> counts = metricBucketService.aggregate(cells, issueClassifier);
    List<AlertCandidate> alerts = alertDetector.detectAll(counts, frozenProfiles);
    metricBucketService.persist(counts, projection);
    baselineService.persistNextStates(counts, frozenProfiles);
    alertService.persist(alerts, projection);
    projection.markSucceeded();
    importFiles.forEach(ImportFile::markProjected);
}
```

Persist links, cells, buckets, profiles and alerts in one transaction. On any exception, mark the `AsyncTask` and `WorkspaceProjection` failed in a separate completion transaction; do not commit partial dashboard facts.

Implement `FeedbackEventRepository.findActiveByWorkspaceIdAndImportFileIds(Long workspaceId, List<Long> fileIds)` with a JPQL join to `AsyncTask` on `FeedbackEvent.ingestedTaskId = AsyncTask.id`, filtering `AsyncTask.importFileId in :fileIds`. This makes a projection read exactly the feedback produced by its frozen source files, even when a Workspace has multiple CSV sources.

- [ ] **Step 4: Implement baseline and alert rules**

```java
boolean shouldAlert = baseline.activeBuckets() >= settings.minHistoryBuckets()
        && count >= Math.max(settings.globalFloor(), Math.round(baseline.ewma() + settings.zThreshold() * sigma))
        && zScore >= settings.zThreshold()
        && !alertRepository.existsRecentOpenOrSent(workspaceId, issueId, bucketStart.minusHours(cooldownHours));
```

Use the frozen profile before writing the next EWMA state. First-baseline buckets persist metrics and next state but return no alert candidate.

- [ ] **Step 5: Run the integration test and build**

Run: `D:\insightflow\mvnw.cmd -Dtest=WorkspaceProjectionTaskRunnerIntegrationTest test` then `D:\insightflow\mvnw.cmd clean package`

Expected: both commands exit `0`; a duplicate dispatch produces no second metric bucket or alert.

### Task 5: Expose the dashboard as read-only Workspace-scoped APIs

**Files:**
- Create: `src/main/java/com/insightflow/service/analysis/DashboardQueryService.java`
- Create: `src/main/java/com/insightflow/dto/analysis/DashboardResponse.java`
- Create: `src/main/java/com/insightflow/dto/analysis/IssueDetailResponse.java`
- Create: `src/main/java/com/insightflow/controller/DashboardController.java`
- Test: `src/test/java/com/insightflow/controller/DashboardControllerTest.java`

**Interfaces:**
- `DashboardQueryService.get(UUID workspacePublicId)` returns only aggregate counts, current alerts, topic summaries and projection state.
- `DashboardQueryService.getIssue(UUID workspacePublicId, UUID issuePublicId)` returns a bounded list of sanitized evidence samples.

- [ ] **Step 1: Write failing MVC contract tests**

```java
mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/dashboard", workspaceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alerts[0].issue_key").value("login_failure"));

mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/issues/{issueId}", otherWorkspace, issueId))
        .andExpect(status().isNotFound());
```

- [ ] **Step 2: Define narrow response records and query methods**

```java
public record DashboardResponse(
        @JsonProperty("data_coverage") TimeRangeResponse dataCoverage,
        List<IssueSummaryResponse> issues,
        List<AlertSummaryResponse> alerts,
        @JsonProperty("projection_status") String projectionStatus) {}
```

The service must first resolve the Workspace UUID to its internal id, then query every repository by that id. Cap evidence output at five sanitized samples; never return `object_key`, hashes, task payloads or internal ids.

- [ ] **Step 3: Implement endpoints**

```java
@GetMapping("/api/v1/workspaces/{workspaceId}/dashboard")
public DashboardResponse dashboard(@PathVariable UUID workspaceId) {
    return dashboardQueryService.get(workspaceId);
}

@GetMapping("/api/v1/workspaces/{workspaceId}/issues/{issueId}")
public IssueDetailResponse issue(@PathVariable UUID workspaceId, @PathVariable UUID issueId) {
    return dashboardQueryService.getIssue(workspaceId, issueId);
}
```

- [ ] **Step 4: Run the controller contract test**

Run: `D:\insightflow\mvnw.cmd -Dtest=DashboardControllerTest test`

Expected: exit code `0`; unknown or cross-Workspace issue identifiers return the existing controlled 404 envelope.

### Task 6: Add optional, read-only report creation and regeneration

**Files:**
- Create: `src/main/java/com/insightflow/dto/analysis/AnalysisReportRequest.java`
- Create: `src/main/java/com/insightflow/dto/analysis/ReportTaskPayload.java`
- Create: `src/main/java/com/insightflow/service/analysis/AnalysisReportAssembler.java`
- Create: `src/main/java/com/insightflow/task/AnalysisReportCommandService.java`
- Create: `src/main/java/com/insightflow/task/AnalysisReportTaskRunner.java`
- Create: `src/main/java/com/insightflow/controller/AnalysisReportController.java`
- Create: `src/main/java/com/insightflow/repository/AnalysisReportRepository.java`
- Test: `src/test/java/com/insightflow/task/AnalysisReportTaskRunnerIntegrationTest.java`
- Test: `src/test/java/com/insightflow/controller/AnalysisReportControllerTest.java`

**Interfaces:**
- `AnalysisReportCommandService.create(UUID workspaceId, AnalysisReportRequest request, String idempotencyKey)` returns an `AnalysisReport` and a queued task.
- `AnalysisReportAssembler.assemble(ReportScope scope, OffsetDateTime snapshotAt)` returns JSON containing only existing dashboard facts and limited evidence.
- `AnalysisReportTaskRunner.run(UUID taskPublicId, String workerId)` writes only `analysis_report.report_json` and task completion fields.

- [ ] **Step 1: Write failing read-only regeneration tests**

```java
@Test
void regeneratingAReportDoesNotChangeMetricsProfilesOrAlerts() {
    Counts before = Counts.capture(metricBucketRepository, profileRepository, alertRepository);
    runReportForSameProjectedFileWithNewIdempotencyKey();
    assertThat(Counts.capture(metricBucketRepository, profileRepository, alertRepository)).isEqualTo(before);
    assertThat(analysisReportRepository.count()).isEqualTo(2);
}
```

- [ ] **Step 2: Validate report scope and snapshot it into the task**

```java
public record AnalysisReportRequest(
        @JsonProperty("file_ids") Set<UUID> fileIds,
        @JsonProperty("time_range") TimeRangeRequest timeRange) {
    public boolean hasScope() {
        return (fileIds != null && !fileIds.isEmpty()) || timeRange != null;
    }
}
```

Reject a request with no scope. Resolve each file under the requested Workspace and require `projection_status='projected'`. When both scope types are present, retain their intersection. Store the resolved file UUIDs and effective time range in immutable `ReportTaskPayload`.

- [ ] **Step 3: Assemble a report without calling any mutation service**

```java
public String assemble(ReportScope scope, OffsetDateTime snapshotAt) {
    return objectMapper.writeValueAsString(new ReportDocument(
            scope, snapshotAt, issueQuery.topIssues(scope), issueQuery.dimensionSummary(scope),
            alertQuery.currentAlerts(scope.workspaceId()), issueQuery.unclassifiedCount(scope)));
}
```

The runner may read `issue_metric_bucket`, `issue_catalog`, `alert`, `feedback_issue_link` and sanitized `FeedbackEvent`, but it must not inject `MetricBucketService`, `EwmaBaselineService`, `AlertDetector` or `WorkspaceProjectionCommandService`.

- [ ] **Step 4: Implement the HTTP contract**

```java
@PostMapping("/api/v1/workspaces/{workspaceId}/analysis-reports")
public ResponseEntity<ReportTaskResponse> create(
        @PathVariable UUID workspaceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody AnalysisReportRequest request) {
    return ResponseEntity.accepted().body(reportService.create(workspaceId, request, idempotencyKey));
}
```

Add `GET /api/v1/workspaces/{workspaceId}/analysis-reports/{reportId}`. Return the report UUID, task status, immutable scope, report JSON and alert summary; do not expose the internal task id or payload.

- [ ] **Step 5: Run report tests and the full suite**

Run: `D:\insightflow\mvnw.cmd -Dtest=AnalysisReportTaskRunnerIntegrationTest,AnalysisReportControllerTest test` then `D:\insightflow\mvnw.cmd test`

Expected: exit code `0`; generating the same report range twice creates two report snapshots only when keys differ, while dashboard facts are unchanged.

### Task 7: Document, verify locally and prepare the review handoff

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-07-20-workspace-analysis-report-and-alert-design.md` only if implementation reveals a necessary contract correction.

- [ ] **Step 1: Document the user-visible state distinction**

Add a README flow showing: upload/mapping/import → automatic projection → dashboard; then optional report generation → read-only report. State explicitly that report regeneration does not re-trigger alerts and that `rebuild_required` needs a future controlled rebuild flow.

- [ ] **Step 2: Run full verification**

Run: `D:\insightflow\mvnw.cmd clean test` and `D:\insightflow\mvnw.cmd package`

Expected: both commands exit `0`.

- [ ] **Step 3: Run a local end-to-end regression against Docker dependencies**

Run: start PostgreSQL and MinIO with `docker compose up -d`, then `D:\insightflow\mvnw.cmd spring-boot:run`.

Expected: Flyway applies V6 and the health endpoint is UP. Upload and import one historical CSV and one spike CSV; verify dashboard changes automatically after each terminal import, then request two reports over the same projected file with distinct keys and verify alert count does not change.

- [ ] **Step 4: Inspect the final diff**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors; changes are limited to the projection/report feature, its migrations, tests, configuration and documentation. Do not commit or push without an explicit user request.
