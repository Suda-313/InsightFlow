# RAG 评测可靠性改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 RAG 评测以可恢复异步任务运行，逐题记录可审计日志、在模型阻塞时按题超时收敛，并产出可用的真实基线。

**Architecture:** 复用现有 `async_task` 持久化队列，新增 `rag_evaluation` 类型而不增加新的数据库表。HTTP 接口提交任务后立即返回公开任务 UUID，调度器以独立线程池领取任务；Worker 对每道题执行受控检索和回答，记录脱敏阶段耗时与 Token，并将完成的聚合结果写入既有 `rag_evaluation_run` 历史表。

**Tech Stack:** Spring Boot、Spring AI OpenAI-compatible client、Spring Data JPA、PostgreSQL、Vue 3、Vitest、JUnit 5、Mockito。

## Global Constraints

- 复用模块化单体和 `async_task`，不引入 MQ、外部 Worker、多 Agent 或新表。
- 所有任务查询、历史写入和轮询接口必须以 `workspace_id` 隔离，对外只暴露公开 UUID。
- 结果、日志和 API 不保存模型原始回答、提示词正文、企业文档正文或原始思维链。
- 单题超时必须同时依赖 HTTP 读超时和业务超时，失败后继续后续题目。
- 先写失败测试并观察到 RED，再写最小实现；不提交测试文件或此次变更。

---

### Task 1: 定义异步 RAG 任务契约与可恢复领取

**Files:**
- Modify: `src/main/java/com/insightflow/entity/AsyncTask.java`
- Modify: `src/main/java/com/insightflow/controller/EvaluationController.java`
- Create: `src/main/java/com/insightflow/evaluation/rag/RagEvaluationTaskCommandService.java`
- Create: `src/test/java/com/insightflow/evaluation/rag/RagEvaluationTaskCommandServiceTest.java`
- Modify: `src/test/java/com/insightflow/controller/RagEvaluationControllerTest.java`

**Interfaces:**
- Produces: `AsyncTask.queuedRagEvaluation(Long workspaceId, String idempotencyKey)`。
- Produces: `RagEvaluationTaskCommandService.enqueue(UUID workspacePublicId): AsyncTask`。
- Produces: `POST /api/v1/workspaces/{workspaceId}/evaluations/rag` 返回 HTTP 202 和 `{ "task_id": "...", "status": "queued" }`。

- [ ] **Step 1: Write the failing task-command test**

```java
@Test
void enqueuesWorkspaceScopedRagEvaluationTask() {
    AsyncTask task = service.enqueue(workspaceId);

    assertThat(task.getTaskType()).isEqualTo("rag_evaluation");
    assertThat(task.getStatus()).isEqualTo("queued");
    verify(tasks).save(task);
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw.cmd -Dtest=RagEvaluationTaskCommandServiceTest test`

Expected: FAIL because `RagEvaluationTaskCommandService` and `queuedRagEvaluation` do not exist.

- [ ] **Step 3: Implement the minimum command service and factory**

```java
public AsyncTask enqueue(UUID workspacePublicId) {
    Workspace workspace = workspaceRepository.findByPublicId(workspacePublicId)
            .orElseThrow(() -> new ResourceNotFoundException("工作区不存在"));
    AsyncTask task = AsyncTask.queuedRagEvaluation(workspace.getId(), UUID.randomUUID().toString());
    return taskRepository.save(task);
}
```

- [ ] **Step 4: Change the controller and test the 202 contract**

```java
@PostMapping("/rag")
@ResponseStatus(HttpStatus.ACCEPTED)
public RagTaskResponse runRagEvaluation(@PathVariable UUID workspaceId) {
    AsyncTask task = ragTaskCommandService.enqueue(workspaceId);
    return RagTaskResponse.from(task);
}
```

- [ ] **Step 5: Run the focused task and controller tests**

Run: `./mvnw.cmd -Dtest=RagEvaluationTaskCommandServiceTest,RagEvaluationControllerTest test`

Expected: PASS; controller does not call `RagLiveEvaluationRunner` on the HTTP request thread.

### Task 2: 增加逐题阶段结果、日志和超时收敛

**Files:**
- Modify: `src/main/java/com/insightflow/evaluation/rag/RagEvaluationCaseResult.java`
- Modify: `src/main/java/com/insightflow/evaluation/rag/RagLiveEvaluationRunner.java`
- Create: `src/main/java/com/insightflow/evaluation/rag/RagEvaluationCaseExecutor.java`
- Create: `src/test/java/com/insightflow/evaluation/rag/RagEvaluationCaseExecutorTest.java`
- Modify: `src/test/java/com/insightflow/evaluation/rag/RagLiveEvaluationRunnerTest.java`

**Interfaces:**
- Produces: 每题结果的 `status`、`failureStage`、检索耗时、生成耗时、总耗时与三项 Token 计数；所有字段均为脱敏数值或固定枚举。
- Produces: `RagEvaluationCaseExecutor.execute(UUID workspaceId, RagEvaluationCaseDefinition definition)`，对检索和生成执行有界调用。
- Produces: INFO 日志 `RAG_EVAL case_id=..., stage=..., status=..., latency_ms=..., prompt_tokens=..., completion_tokens=..., total_tokens=...`。

- [ ] **Step 1: Write the failing timeout test**

```java
@Test
void marksOnlyTimedOutCaseFailedAndContinues() {
    when(search.retrieve(any(), eq("慢题"))).thenAnswer(ignored -> {
        Thread.sleep(200);
        return retrieval;
    });

    RagEvaluationCaseExecution result = executor.execute(workspaceId, slowCase);

    assertThat(result.status()).isEqualTo("failed");
    assertThat(result.failureStage()).isEqualTo("timeout");
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw.cmd -Dtest=RagEvaluationCaseExecutorTest test`

Expected: FAIL because `RagEvaluationCaseExecutor` does not exist.

- [ ] **Step 3: Implement a bounded per-case executor**

```java
try {
    return future.get(caseTimeoutSeconds, TimeUnit.SECONDS);
} catch (TimeoutException exception) {
    future.cancel(true);
    return RagEvaluationCaseExecution.timedOut(definition);
}
```

Use a dedicated bounded executor for evaluation calls; the HTTP transport timeout is configured in Task 3 so cancellation does not leave requests indefinitely blocked.

- [ ] **Step 4: Extend the runner with structured logs and safe metrics**

```java
log.info("RAG_EVAL case_id={}, stage={}, status={}, latency_ms={}, prompt_tokens={}, completion_tokens={}, total_tokens={}",
        execution.caseId(), execution.failureStage(), execution.status(), execution.totalLatencyMs(),
        execution.promptTokens(), execution.completionTokens(), execution.totalTokens());
```

Ensure normal answers keep existing retrieval/citation scoring semantics and failed or timed-out answers use empty evidence only.

- [ ] **Step 5: Run focused RAG runner tests**

Run: `./mvnw.cmd -Dtest=RagEvaluationCaseExecutorTest,RagLiveEvaluationRunnerTest test`

Expected: PASS; a timeout is represented by a failed case and does not prevent later cases from being evaluated.

### Task 3: 配置模型 HTTP 超时与异步 Worker 生命周期

**Files:**
- Modify: `src/main/java/com/insightflow/config/AgentConfiguration.java`
- Modify: `src/main/java/com/insightflow/config/ImportTaskConfiguration.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/insightflow/evaluation/rag/RagEvaluationLeaseService.java`
- Create: `src/main/java/com/insightflow/evaluation/rag/RagEvaluationScheduler.java`
- Create: `src/main/java/com/insightflow/evaluation/rag/RagEvaluationTaskRunner.java`
- Create: `src/main/java/com/insightflow/evaluation/rag/RagEvaluationTaskCompletionService.java`
- Create: `src/test/java/com/insightflow/evaluation/rag/RagEvaluationTaskRunnerTest.java`

**Interfaces:**
- Produces: `insightflow.evaluation.rag.case-timeout-seconds`、`http-read-timeout-seconds`、`dispatch-delay-ms`、`lease-seconds` 配置。
- Produces: 仅由持有租约的 Worker 调用 `RagLiveEvaluationRunner.run(workspaceId)` 并把最终结果写入 `RagEvaluationHistoryService`。
- Produces: 成功任务的 `result_json={"run":"<public UUID>"}`；失败任务只保存固定错误码和用户可见的简短摘要。

- [ ] **Step 1: Write the failing Worker completion test**

```java
@Test
void persistsResultAndMarksLeasedTaskSucceeded() {
    runner.run(taskId, workerId);

    verify(history).record(workspacePublicId, result);
    assertThat(task.getStatus()).isEqualTo("succeeded");
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw.cmd -Dtest=RagEvaluationTaskRunnerTest test`

Expected: FAIL because the RAG worker lifecycle classes do not exist.

- [ ] **Step 3: Implement lease, scheduler, runner, and completion service**

```java
@Scheduled(fixedDelayString = "${insightflow.evaluation.rag.dispatch-delay-ms:5000}")
public void scheduledDispatch() {
    leaseService.claimNext(workerId).ifPresent(taskId -> taskRunner.run(taskId, workerId));
}
```

Keep the scheduler pattern aligned with `AnalysisReportScheduler`; task ownership must be rechecked before persistence.

- [ ] **Step 4: Configure transport and task limits**

```yaml
insightflow:
  evaluation:
    rag:
      case-timeout-seconds: ${RAG_EVALUATION_CASE_TIMEOUT_SECONDS:45}
      http-read-timeout-seconds: ${RAG_EVALUATION_HTTP_READ_TIMEOUT_SECONDS:40}
      dispatch-delay-ms: ${RAG_EVALUATION_DISPATCH_DELAY_MS:5000}
      lease-seconds: ${RAG_EVALUATION_TASK_LEASE_SECONDS:360}
```

Apply the client request factory timeout to both chat and embedding clients created by `AgentConfiguration`; do not log any endpoint credentials or request content.

- [ ] **Step 5: Run Worker tests**

Run: `./mvnw.cmd -Dtest=RagEvaluationTaskRunnerTest,RagEvaluationTaskCommandServiceTest test`

Expected: PASS; result persistence occurs only for the same Workspace and lease owner.

### Task 4: 提供任务状态读取并让前端轮询

**Files:**
- Modify: `src/main/java/com/insightflow/controller/EvaluationController.java`
- Create: `src/main/java/com/insightflow/evaluation/rag/RagEvaluationTaskQueryService.java`
- Modify: `src/test/java/com/insightflow/controller/RagEvaluationControllerTest.java`
- Modify: `frontend/src/views/Evaluations.vue`
- Modify: `frontend/test/Evaluations.spec.js`

**Interfaces:**
- Produces: `GET /api/v1/workspaces/{workspaceId}/evaluations/rag/tasks/{taskId}`，返回 `{task_id,status,run_id?,error_code?}`。
- Consumes: POST 创建的 `task_id`。
- Produces: 前端在 `queued`/`running` 时每 2 秒轮询，终态后停止、刷新 RAG 历史并展示最终指标。

- [ ] **Step 1: Write failing task-query test**

```java
@Test
void rejectsTaskFromAnotherWorkspace() {
    assertThatThrownBy(() -> query.get(otherWorkspaceId, taskId))
            .isInstanceOf(ResourceNotFoundException.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -Dtest=RagEvaluationControllerTest test`

Expected: FAIL because the task status endpoint is missing.

- [ ] **Step 3: Add minimal query endpoint and DTO**

```java
public RagTaskStatusResponse get(UUID workspacePublicId, UUID taskPublicId) {
    AsyncTask task = taskRepository.findByPublicId(taskPublicId)
            .filter(item -> item.getWorkspaceId().equals(workspace.getId()))
            .filter(item -> "rag_evaluation".equals(item.getTaskType()))
            .orElseThrow(() -> new ResourceNotFoundException("评测任务不存在"));
    return RagTaskStatusResponse.from(task);
}
```

- [ ] **Step 4: Write and run the failing Vue polling test**

```js
it('submits a RAG task then polls until it succeeds', async () => {
  fetch.mockResolvedValueOnce(jsonResponse({ task_id: 'task-1', status: 'queued' }))
       .mockResolvedValueOnce(jsonResponse({ task_id: 'task-1', status: 'succeeded', run_id: 'run-1' }))
  await wrapper.vm.runRagEvaluation()
  expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/rag/tasks/task-1'), undefined)
})
```

- [ ] **Step 5: Implement polling and run frontend tests**

Run: `npm --prefix frontend test -- --run Evaluations.spec.js`

Expected: PASS; request lifecycle does not keep the HTTP POST open waiting for model calls.

### Task 5: 验证完整链路并重跑真实基线

**Files:**
- Modify: `docs/project-development-log.md`
- Modify: `docs/agent-optimization-todo.md`

**Interfaces:**
- Consumes: 已发布的 Workspace 知识文档、运行中的本地服务和真实模型配置。
- Produces: 一个 `rag_evaluation_run` 历史记录及对应成功的 `async_task`，或清楚标记的超时/失败原因。

- [ ] **Step 1: Run backend focused regression suite**

Run: `./mvnw.cmd -Dtest=RagEvaluationCaseExecutorTest,RagLiveEvaluationRunnerTest,RagEvaluationTaskCommandServiceTest,RagEvaluationTaskRunnerTest,RagEvaluationControllerTest,RagEvaluationHistoryServiceTest test`

Expected: PASS.

- [ ] **Step 2: Build the frontend**

Run: `npm --prefix frontend run build`

Expected: build exits 0; generated static assets are inspected but not automatically committed.

- [ ] **Step 3: Run the local real-data evaluation**

Run: create the RAG task through the authenticated local API, poll until terminal status, then fetch `GET /evaluations/rag`.

Expected: task reaches `succeeded`; history contains a new run with its dataset/prompt/model/retrieval versions and finite metrics. If the provider times out, record the terminal task/error and do not label it a valid baseline.

- [ ] **Step 4: Record only verified facts**

Update `docs/project-development-log.md` with the original blocking symptom, root cause, task/timeout decision, focused test evidence, and real run outcome. Update the Todo item to distinguish completed reliability work from any remaining provider-performance optimization.

## Self-Review

- Spec coverage: Task 2 implements per-case logs and failure phase; Task 2 + Task 3 enforce per-case and transport timeouts; Tasks 1, 3, and 4 implement asynchronous lifecycle; Task 5 runs and verifies the real baseline.
- Placeholder scan: no unresolved implementation placeholders or generic error-handling steps remain.
- Type consistency: the controller consumes `RagEvaluationTaskCommandService` and `RagEvaluationTaskQueryService`; the Worker persists through `RagEvaluationHistoryService`; `RagEvaluationRun` remains the final immutable history record.
