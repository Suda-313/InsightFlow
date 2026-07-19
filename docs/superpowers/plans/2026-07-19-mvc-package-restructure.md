# MVC Package Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize InsightFlow into a traditional MVC-oriented package layout with a dedicated asynchronous task module, without changing runtime behavior.

**Architecture:** Controllers remain the HTTP boundary, services coordinate synchronous use cases, entities and repositories represent persistence, and `task` contains the durable CSV task runtime. All classes keep their existing names; only filesystem location, Java package declarations, imports, documentation paths, and tests' package declarations change.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, Flyway, MinIO, Maven Wrapper.

## Global Constraints

- Do not rename Java classes, alter REST paths or response fields, or change database/Flyway V1--V5 behavior.
- Do not add tests; retain and run the existing five Maven tests plus the local CSV regression.
- Preserve `workspace_id` filtering, UUIDv7 public IDs, MinIO raw-file storage, and PostgreSQL-only sanitized feedback storage.
- Keep the project as a modular monolith; do not add queues, microservices, Agent changes, or infrastructure components.
- Preserve meaningful comments while updating package-path wording where it becomes inaccurate.

---

### Task 1: Move persistence and configuration classes into MVC infrastructure packages

**Files:**
- Move `src/main/java/com/insightflow/importing/domain/AsyncTask.java` to `src/main/java/com/insightflow/entity/AsyncTask.java`
- Move `FeedbackEvent.java`, `FeedbackSource.java`, `ImportFile.java`, and `workspace/domain/Workspace.java` to `src/main/java/com/insightflow/entity/`
- Move all five `*Repository.java` files to `src/main/java/com/insightflow/repository/`
- Move `ImportTaskConfiguration.java` and `MinioStorageConfiguration.java` to `src/main/java/com/insightflow/config/`
- Move `MinioRawImportObjectStorage.java`, `RawImportObjectStorage.java`, and `RawObjectStorageException.java` to `src/main/java/com/insightflow/storage/`

**Interfaces:**
- Preserves all public class names, Spring stereotypes, JPA mappings, repository method signatures, and configuration properties.
- Produces the imports later tasks use: `com.insightflow.entity.*`, `repository.*`, `config.*`, and `storage.*`.

- [ ] **Step 1: Use version-control-aware moves for every listed Java file**

```powershell
git mv src/main/java/com/insightflow/importing/domain/*.java src/main/java/com/insightflow/entity/
git mv src/main/java/com/insightflow/workspace/domain/Workspace.java src/main/java/com/insightflow/entity/
git mv src/main/java/com/insightflow/importing/infrastructure/*Repository.java src/main/java/com/insightflow/repository/
git mv src/main/java/com/insightflow/workspace/infrastructure/WorkspaceRepository.java src/main/java/com/insightflow/repository/
```

Create `config/` and `storage/` before moving their files; move only the named configuration and MinIO storage files.

- [ ] **Step 2: Change each package declaration to its target package and repair imports**

```java
package com.insightflow.entity;
package com.insightflow.repository;
package com.insightflow.config;
package com.insightflow.storage;
```

Keep all annotations such as `@Entity`, `@Repository`, `@Configuration`, `@Bean`, and `@Component` unchanged.

- [ ] **Step 3: Compile the structural move**

Run: `D:\insightflow\mvnw.cmd -q -DskipTests compile`

Expected: exit code `0`; no class references the old `domain` or `infrastructure` packages.

### Task 2: Move synchronous HTTP and service classes into MVC packages

**Files:**
- Move `importing/api/FileImportController.java` and `workspace/api/WorkspaceController.java` to `src/main/java/com/insightflow/controller/`
- Move `importing/application/FileImportService.java` and `workspace/application/WorkspaceService.java` to `src/main/java/com/insightflow/service/`
- Move `CsvFormatSupport.java`, `CsvPreviewReader.java`, `HashingService.java`, `ImportMappingValidator.java`, and `PiiSanitizer.java` to `src/main/java/com/insightflow/service/importing/`
- Move `ImportMapping.java`, `ImportTaskPayload.java`, and `ImportTaskResult.java` to `src/main/java/com/insightflow/dto/importing/`
- Move `ImportFileNotFoundException.java`, `ImportValidationException.java`, `WorkspaceNotFoundException.java`, and `shared/api/ApiExceptionHandler.java` to `src/main/java/com/insightflow/common/exception/`

**Interfaces:**
- HTTP mappings and request/response records keep their current signatures.
- `FileImportService` remains the only synchronous file-import use-case facade.
- `ImportMapping`, `ImportTaskPayload`, and `ImportTaskResult` remain immutable records with their existing JSON annotations.

- [ ] **Step 1: Move Controllers, Services, DTOs, and exceptions to the exact target paths**

```powershell
git mv src/main/java/com/insightflow/importing/api/FileImportController.java src/main/java/com/insightflow/controller/
git mv src/main/java/com/insightflow/workspace/api/WorkspaceController.java src/main/java/com/insightflow/controller/
git mv src/main/java/com/insightflow/importing/application/FileImportService.java src/main/java/com/insightflow/service/
git mv src/main/java/com/insightflow/workspace/application/WorkspaceService.java src/main/java/com/insightflow/service/
```

Move the remaining named support, DTO, and exception classes to the paths listed above.

- [ ] **Step 2: Update package declarations and all imports without changing method bodies**

```java
package com.insightflow.controller;
package com.insightflow.service;
package com.insightflow.service.importing;
package com.insightflow.dto.importing;
package com.insightflow.common.exception;
```

For example, controllers import `com.insightflow.service.FileImportService`; services import `entity`, `repository`, `dto.importing`, and `storage` classes.

- [ ] **Step 3: Compile before task runtime moves**

Run: `D:\insightflow\mvnw.cmd -q -DskipTests compile`

Expected: exit code `0`; `@RestControllerAdvice` remains discovered below `com.insightflow`.

### Task 3: Move the durable CSV runtime into the dedicated task package

**Files:**
- Move `ImportTaskCommandService.java`, `ImportTaskCompletionService.java`, `ImportTaskLeaseService.java`, `ImportTaskRunner.java`, and `ImportTaskScheduler.java` from `importing/application/` to `src/main/java/com/insightflow/task/`

**Interfaces:**
- `ImportTaskCommandService.start(UUID, UUID, String)` continues creating an immutable mapped task.
- `ImportTaskScheduler.dispatchClaimableTasks()` remains the transaction-after-commit and scheduled dispatch entry point.
- `ImportTaskRunner.run(UUID, String)` retains asynchronous execution and lease-owner validation.

- [ ] **Step 1: Move the five runtime classes as one cohesive package**

```powershell
git mv src/main/java/com/insightflow/importing/application/ImportTaskCommandService.java src/main/java/com/insightflow/task/
git mv src/main/java/com/insightflow/importing/application/ImportTaskCompletionService.java src/main/java/com/insightflow/task/
git mv src/main/java/com/insightflow/importing/application/ImportTaskLeaseService.java src/main/java/com/insightflow/task/
git mv src/main/java/com/insightflow/importing/application/ImportTaskRunner.java src/main/java/com/insightflow/task/
git mv src/main/java/com/insightflow/importing/application/ImportTaskScheduler.java src/main/java/com/insightflow/task/
```

- [ ] **Step 2: Replace old runtime imports with `com.insightflow.task.*`**

Keep `@Async`, `@Scheduled`, transaction propagation, lease behavior, and completion transactions exactly as they are. Import DTOs from `com.insightflow.dto.importing`, support services from `com.insightflow.service.importing`, and persistence from `entity/repository`.

- [ ] **Step 3: Compile the full application**

Run: `D:\insightflow\mvnw.cmd -q -DskipTests compile`

Expected: exit code `0`; the `@EnableAsync` and `@EnableScheduling` application entry point discovers all runtime beans.

### Task 4: Move test packages and update human-facing architecture references

**Files:**
- Move existing tests from `src/test/java/com/insightflow/importing/application/` to packages matching their moved production types:
  - `CsvPreviewReaderTest.java`, `ImportMappingValidatorTest.java`, `PiiSanitizerTest.java` → `src/test/java/com/insightflow/service/importing/`
- Modify `README.md`
- Modify JavaDoc comments only where they explicitly name an obsolete package layer such as `application`, `domain`, or `infrastructure` rather than describe the class responsibility.

**Interfaces:**
- Test assertions, fixture file `src/test/resources/fixtures/feedback-import.csv`, and production behavior remain unchanged.
- README describes the MVC + task directory structure and current CSV runtime flow.

- [ ] **Step 1: Move the three existing tests and align their package declarations/imports**

```powershell
git mv src/test/java/com/insightflow/importing/application/CsvPreviewReaderTest.java src/test/java/com/insightflow/service/importing/
git mv src/test/java/com/insightflow/importing/application/ImportMappingValidatorTest.java src/test/java/com/insightflow/service/importing/
git mv src/test/java/com/insightflow/importing/application/PiiSanitizerTest.java src/test/java/com/insightflow/service/importing/
```

Do not add test files or alter test assertions.

- [ ] **Step 2: Replace README architecture paths with the target MVC layout**

Document `controller → service → repository/entity`, plus `task → storage/repository` for durable CSV execution. Do not claim that the project is full DDD.

- [ ] **Step 3: Search for stale package declarations and imports**

Run: `rg "com\.insightflow\.(importing|workspace|shared\.api)" src README.md`

Expected: no Java package/import matches; references inside historical design documents may remain unchanged as historical context.

### Task 5: Verify no behavior change and commit the refactor separately

**Files:**
- Review all moved Java files, test files, `README.md`, and MVC design/plan documents.

- [ ] **Step 1: Run existing tests and package**

Run: `D:\insightflow\mvnw.cmd test` and `D:\insightflow\mvnw.cmd -q package`

Expected: 5 tests pass and Maven exits `0`.

- [ ] **Step 2: Run local CSV regression against Docker dependencies**

Start the application, then verify the existing flow: upload UTF-8 CSV → save mapping → submit same idempotency key twice → verify same task UUID → poll terminal result → verify a duplicate-header upload returns `VALIDATION_FAILED`.

Expected: status and JSON fields match the pre-refactor contract exactly.

- [ ] **Step 3: Inspect the structural diff**

Run: `git diff --check`, `git diff --name-status`, and `git status --short`.

Expected: no whitespace errors; files appear primarily as renames plus package/import/README changes; no migration or application YAML changes.

- [ ] **Step 4: Commit only after successful verification**

Run: `git add src README.md docs/superpowers && git commit -m "refactor: reorganize packages around mvc"`

Expected: one behavior-preserving refactor commit on `codex/csv-import-reliability`.
