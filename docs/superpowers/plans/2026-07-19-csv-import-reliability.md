# CSV Import Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make CSV imports deterministic, recoverable after process interruption, concurrency-safe, and contract-compatible without adding new infrastructure.

**Architecture:** Keep a modular Spring Boot monolith. PostgreSQL remains the durable task coordinator: a scheduler claims queued or expired leased tasks, and the worker finalizes each task in a separate transaction. The task payload contains an immutable mapping snapshot, while MinIO remains the sole store of raw CSV bytes.

**Tech Stack:** Java 17, Spring Boot 3, Spring Data JPA, PostgreSQL 16, Flyway, Apache Commons CSV, MinIO.

## Global Constraints

- Do not add Redis queues, Kafka, a worker process, a microservice, or multi-Agent orchestration.
- Do not edit Flyway migrations V1--V4; add a new forward-only migration.
- Keep every business read and write scoped by `workspace_id`.
- Preserve raw-CSV-in-MinIO and sanitized-data-in-PostgreSQL separation.
- Do not add unit-test files at the user's request; run the existing Maven tests and one local CSV regression.
- Keep meaningful comments in business, persistence and async-task modules at no less than one comment line per two code lines.

---

### Task 1: Add durable task lease fields and repository claim operations

**Files:**
- Create: `src/main/resources/db/migration/V5__add_async_task_leasing.sql`
- Modify: `src/main/java/com/insightflow/importing/domain/AsyncTask.java`
- Modify: `src/main/java/com/insightflow/importing/infrastructure/AsyncTaskRepository.java`

**Interfaces:**
- Produces `AsyncTask.claim(String workerId, OffsetDateTime leaseUntil)` and `AsyncTask.isLeaseOwnedBy(String workerId)`.
- Produces `AsyncTaskRepository.findNextClaimableImportTask(OffsetDateTime now, Pageable pageable)` using `PESSIMISTIC_WRITE` / `SKIP LOCKED` semantics.
- Makes the task payload immutable after task creation and persists `lease_owner`, `lease_expires_at`, `started_at`, and `finished_at`.

- [ ] **Step 1: Add only a forward migration**

```sql
ALTER TABLE async_task
    ADD COLUMN lease_owner VARCHAR(100),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ;

CREATE INDEX idx_async_task_claimable
    ON async_task (task_type, status, lease_expires_at, created_at);
```

- [ ] **Step 2: Add lease-aware entity transitions**

```java
public void claim(String workerId, OffsetDateTime leaseUntil) {
    this.status = "running";
    this.leaseOwner = workerId;
    this.leaseExpiresAt = leaseUntil;
    this.attemptCount++;
    this.startedAt = OffsetDateTime.now();
    this.updatedAt = this.startedAt;
}

public boolean canBeClaimedAt(OffsetDateTime now) {
    return "queued".equals(status)
            || ("running".equals(status) && leaseExpiresAt != null && !leaseExpiresAt.isAfter(now));
}
```

- [ ] **Step 3: Make terminal transitions clear lease fields and stamp completion time**

```java
private void finish() {
    this.leaseOwner = null;
    this.leaseExpiresAt = null;
    this.finishedAt = OffsetDateTime.now();
    this.updatedAt = this.finishedAt;
}
```

- [ ] **Step 4: Verify compilation after the persistence-layer changes**

Run: `D:\insightflow\mvnw.cmd -q -DskipTests compile`

Expected: exit code `0`.

### Task 2: Freeze mapping at task creation and serialize starts per file

**Files:**
- Modify: `src/main/java/com/insightflow/importing/application/ImportTaskPayload.java`
- Modify: `src/main/java/com/insightflow/importing/application/FileImportService.java`
- Modify: `src/main/java/com/insightflow/importing/domain/ImportFile.java`
- Modify: `src/main/java/com/insightflow/importing/infrastructure/ImportFileRepository.java`

**Interfaces:**
- `ImportTaskPayload(UUID fileId, ImportMapping mapping)` is the immutable worker input.
- `ImportFileRepository.findByWorkspaceIdAndPublicIdForUpdate(Long workspaceId, UUID publicId)` locks a file for `start`.
- `FileImportService.start(...)` returns the existing task for an identical idempotent request and rejects a different file using the same key.

- [ ] **Step 1: Include the validated mapping in task payload JSON**

```java
private String writePayload(ImportFile file) {
    ImportMapping mapping = readMapping(file);
    if (mapping == null) {
        throw new ImportValidationException("导入映射不存在。");
    }
    return objectMapper.writeValueAsString(new ImportTaskPayload(file.getPublicId(), mapping));
}
```

- [ ] **Step 2: Lock the file and transition it to `processing` with task creation**

```java
ImportFile file = importFileRepository
        .findByWorkspaceIdAndPublicIdForUpdate(workspace.getId(), filePublicId)
        .orElseThrow(() -> new ImportFileNotFoundException(filePublicId));
if (!"mapped".equals(file.getStatus())) {
    return existingTaskOrReject(file, idempotencyKey, payload);
}
file.markProcessing();
```

- [ ] **Step 3: Re-read an idempotency collision as the business result**

```java
try {
    return taskRepository.saveAndFlush(newTask);
} catch (DataIntegrityViolationException exception) {
    AsyncTask existing = findExistingTaskOrThrow(...);
    if (!sameJson(existing.getPayloadJson(), payload)) {
        throw new ImportValidationException("同一 Idempotency-Key 不能用于不同导入文件。");
    }
    return existing;
}
```

- [ ] **Step 4: Reject mapping writes once `processing` starts**

`saveMapping` continues accepting only `uploaded` and `mapped`; `start` changes status before transaction commit, so any later mapping request returns `VALIDATION_FAILED`.

### Task 3: Replace after-commit-only execution with a recoverable database scheduler

**Files:**
- Create: `src/main/java/com/insightflow/importing/application/ImportTaskScheduler.java`
- Modify: `src/main/java/com/insightflow/importing/application/ImportTaskRunner.java`
- Modify: `src/main/java/com/insightflow/InsightFlowApplication.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- `ImportTaskScheduler.dispatchClaimableTasks()` runs at `insightflow.import.dispatch-delay-ms` and invokes `ImportTaskRunner.run(UUID, String)` only after a task has been leased.
- `ImportTaskRunner.run(UUID taskPublicId, String workerId)` reads `ImportTaskPayload.mapping()` and finalizes using the matching lease owner.

- [ ] **Step 1: Enable scheduling and add bounded configuration**

```yaml
insightflow:
  import:
    dispatch-delay-ms: ${IMPORT_DISPATCH_DELAY_MS:5000}
    lease-seconds: ${IMPORT_TASK_LEASE_SECONDS:120}
    max-dispatch-per-cycle: ${IMPORT_MAX_DISPATCH_PER_CYCLE:4}
```

Add `@EnableScheduling` next to the existing async support in `InsightFlowApplication`.

- [ ] **Step 2: Implement short-transaction claim logic**

```java
@Transactional
public Optional<ClaimedTask> claimOne() {
    OffsetDateTime now = OffsetDateTime.now();
    AsyncTask task = taskRepository.findNextClaimableImportTask(now, PageRequest.of(0, 1))
            .stream().findFirst().orElse(null);
    if (task == null) return Optional.empty();
    if (task.getAttemptCount() >= task.getMaxAttempts()) {
        task.markFailed("IMPORT_RETRY_EXHAUSTED", "导入任务重试次数已耗尽。");
        return Optional.empty();
    }
    task.claim(workerId, now.plusSeconds(leaseSeconds));
    return Optional.of(new ClaimedTask(task.getPublicId(), workerId));
}
```

- [ ] **Step 3: Make the worker use the frozen mapping and finalize in an independent transaction**

```java
ImportTaskPayload payload = readPayload(task);
ImportMapping mapping = payload.mapping();
ImportTaskResult result = importFile(file, task, mapping);
task.markSucceeded(resultJson); // or partial/failed
```

The finalization method must verify `task.isLeaseOwnedBy(workerId)` before changing state. A failure must be persisted in a new transaction so an earlier data-access exception cannot roll back the failure marker.

- [ ] **Step 4: Preserve low-latency dispatch without making it the sole recovery path**

After the `start` transaction commits, optionally signal the scheduler/runner. The scheduled database scan remains authoritative for crash recovery.

### Task 4: Unify CSV header validation and API response contract

**Files:**
- Create: `src/main/java/com/insightflow/importing/application/CsvFormatSupport.java`
- Modify: `src/main/java/com/insightflow/importing/application/CsvPreviewReader.java`
- Modify: `src/main/java/com/insightflow/importing/application/ImportTaskRunner.java`
- Modify: `src/main/java/com/insightflow/shared/api/ApiExceptionHandler.java`
- Modify: `src/main/java/com/insightflow/importing/api/FileImportController.java`
- Modify: `src/main/java/com/insightflow/importing/application/FileImportService.java`

**Interfaces:**
- `CsvFormatSupport.parse(InputStream)` exposes a parser configuration shared by preview and worker.
- `CsvFormatSupport.validateHeaders(List<String>)` strips BOM, rejects blank or duplicate headers, and returns the normalized header list.
- `ApiExceptionHandler` returns `ErrorEnvelope(ErrorBody error)` with uppercase error codes and `trace_id`.

- [ ] **Step 1: Validate normalized headers before samples or mapping can be produced**

```java
Set<String> seen = new HashSet<>();
for (String header : normalizedHeaders) {
    if (header.isBlank() || !seen.add(header)) {
        throw new ImportValidationException("CSV 表头不能为空且不得重复。");
    }
}
```

- [ ] **Step 2: Reuse that validation before building worker indexes**

The worker must call the same helper before `buildHeaderIndexes`; it must not rely on map overwrite behavior for duplicate columns.

- [ ] **Step 3: Return the documented error envelope and snake_case fields**

```java
public record ErrorEnvelope(ErrorBody error) {}
public record ErrorBody(
        String code, String message,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("field_errors") List<FieldError> fieldErrors) {}
```

Map existing exception categories to `RESOURCE_NOT_FOUND`, `VALIDATION_FAILED`, `FILE_TOO_LARGE`, and `DEPENDENCY_UNAVAILABLE`; generate one opaque trace UUID per error response. Annotate CSV import response records with `@JsonProperty` for `created_at`, `file_id`, `file_status`, `task_id`, and `task_status`.

### Task 5: Validate the existing implementation and push the branch

**Files:**
- Modify: `README.md` only if endpoint behavior or run instructions changed materially.

- [ ] **Step 1: Inspect migration and application logs**

Run: `D:\insightflow\mvnw.cmd test`

Expected: exit code `0`; no new test file is created.

- [ ] **Step 2: Build and start against local Docker dependencies**

Run: `D:\insightflow\mvnw.cmd -q clean package` and then `D:\insightflow\mvnw.cmd spring-boot:run`.

Expected: Flyway applies V5; health endpoint reports UP.

- [ ] **Step 3: Perform a local CSV regression**

Upload a valid two-row UTF-8 CSV, save its mapping, start with an idempotency key, submit the same start request again, and poll result until terminal. Verify the second start has the same task UUID and sanitized feedback records are present. Upload a CSV with duplicated headers and verify a 422 `VALIDATION_FAILED` envelope.

- [ ] **Step 4: Review the final diff and Git status**

Run: `git diff --check`, `git status --short`, and `git diff --stat`.

Expected: no whitespace errors; only the reliability implementation, its Flyway migration, and its design/plan documents are included.

- [ ] **Step 5: Create the initial commit and push the feature branch**

Run: `git add ...`, `git commit -m "feat: harden csv import task execution"`, and `git push -u origin codex/csv-import-reliability`.

Expected: a remote branch is created successfully. Do not merge to `main` in this task.
