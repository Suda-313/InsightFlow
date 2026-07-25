# P4 业务闭环与权限协作 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不接入外部协作平台的前提下，交付有身份权限、异步调查、人工处置、纠错评测、证据化报告和收敛前端体验的 P4 闭环。

**Architecture:** `Alert` 继续作为不可变触发事实；新的调查卡片、提案、执行记录和审计记录承载后续可变流程。Spring Security 解析 JWT 身份，服务层根据数据库成员关系和 Workspace 范围授权；Agent 只产生只读调查证据与提案，所有状态写入经 Command Service 完成。

**Tech Stack:** Java 17、Spring Boot 3.5、Spring Security、JPA、Flyway/PostgreSQL、Vue 3/Pinia、Vite、JUnit 5、Node Test Runner。

## Global Constraints

- 保持模块化单体，不引入多 Agent、微服务、外部协作平台或未经确认的数据源。
- 所有业务读写按 `workspace_id` 隔离；HTTP API 仅暴露 UUID `public_id`。
- Agent 只读调查；写操作必须经过权限、提案、人工确认、幂等键、审计和 Command Service。
- 密码只保存 BCrypt 哈希；JWT 密钥和 bootstrap 口令仅来自部署环境，禁止默认密码或提交密钥。
- 关键 Java 实体、迁移、API、异步任务与护栏保持中文有效注释不少于非空代码的 1/2。
- 测试必须按 TDD 编写和执行，但遵循用户要求：测试源码不进入本次提交。
- 前端请求必须显式处理加载、空态、权限不足和失败状态；构建产物仅提交当前 `index.html` 引用的文件。

---

### Task 1: 本地认证、组织成员和 Workspace 授权

**Files:**
- Modify: `pom.xml`, `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V15__add_identity_and_membership_schema.sql`
- Create: `src/main/java/com/insightflow/security/{AppUser,OrganizationMember,WorkspaceMember,MemberRole}.java`
- Create: `src/main/java/com/insightflow/security/{JwtTokenService,CurrentUser,WorkspaceAccessService,SecurityConfiguration}.java`
- Create: `src/main/java/com/insightflow/controller/{AuthController,MemberController}.java`
- Test: `src/test/java/com/insightflow/security/{JwtTokenServiceTest,WorkspaceAccessServiceTest}.java`

**Interfaces:**
- Produces `CurrentUser requireCurrentUser()` and `WorkspaceAccessService.requireRole(UUID workspaceId, MemberRole... roles)` for all P4 command services.
- Produces `POST /api/v1/auth/bootstrap`, `POST /api/v1/auth/login` and Workspace-scoped member APIs.

- [ ] **Step 1: Write failing authorization tests**

```java
@Test
void rejectsUserWithoutWorkspaceMembership() {
    when(members.findByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> access.requireRead(workspacePublicId))
            .isInstanceOf(WorkspaceAccessDeniedException.class);
}
```

- [ ] **Step 2: Run the focused tests and observe authorization types are absent**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd -Dtest=JwtTokenServiceTest,WorkspaceAccessServiceTest test`

- [ ] **Step 3: Add minimal schema and security implementation**

```java
public void requireRole(UUID workspacePublicId, MemberRole... roles) {
    Workspace workspace = workspaceService.get(workspacePublicId);
    WorkspaceMember member = membershipRepository.findByUserIdAndWorkspaceId(currentUser.id(), workspace.getId())
            .orElseThrow(() -> new WorkspaceAccessDeniedException(workspacePublicId));
    if (!List.of(roles).contains(member.getRole())) throw new WorkspaceAccessDeniedException(workspacePublicId);
}
```

- [ ] **Step 4: Re-run focused tests and then the security/controller suite**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd -Dtest=JwtTokenServiceTest,WorkspaceAccessServiceTest,AuthControllerTest,MemberControllerTest test`

### Task 2: 审计基础设施与工作区 API 授权接入

**Files:**
- Create: `src/main/resources/db/migration/V16__add_audit_log_schema.sql`
- Create: `src/main/java/com/insightflow/{entity/AuditLog.java,repository/AuditLogRepository.java,service/AuditLogService.java}`
- Modify: `src/main/java/com/insightflow/{controller/DashboardController.java,controller/ChatController.java,controller/KnowledgeDocumentController.java,controller/ReportController.java,controller/EvaluationController.java}`
- Test: `src/test/java/com/insightflow/service/AuditLogServiceTest.java`

**Interfaces:**
- Consumes Task 1 `CurrentUser` 和 `WorkspaceAccessService`。
- Produces `AuditLogService.record(UUID workspaceId, String action, UUID targetId, String summary)`。

- [ ] **Step 1: Write a failing audit isolation test**

```java
@Test
void storesActorAndWorkspaceWithoutRawCommandPayload() {
    AuditLog log = service.record(workspacePublicId, "proposal.executed", proposalId, "action=CONFIRM");
    assertThat(log.getWorkspaceId()).isEqualTo(workspace.getId());
    assertThat(log.getSummary()).doesNotContain("password");
}
```

- [ ] **Step 2: Run it, implement V16/entity/service, and add service-boundary access checks**

```java
@Transactional
public AuditLog record(UUID workspacePublicId, String action, UUID targetPublicId, String summary) {
    Workspace workspace = access.requireRead(workspacePublicId);
    return repository.save(AuditLog.of(workspace.getId(), currentUser.publicId(), action, targetPublicId, summary));
}
```

- [ ] **Step 3: Verify the audit and existing controller tests**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd -Dtest=AuditLogServiceTest,DashboardControllerTest,ChatControllerTest,KnowledgeDocumentControllerTest,ReportControllerTest test`

### Task 3: 异步调查卡片与受控证据快照

**Files:**
- Create: `src/main/resources/db/migration/V17__add_investigation_case_schema.sql`
- Create: `src/main/java/com/insightflow/{entity/InvestigationCase.java,entity/InvestigationEvidenceSnapshot.java,repository/InvestigationCaseRepository.java,repository/InvestigationEvidenceSnapshotRepository.java}`
- Create: `src/main/java/com/insightflow/investigation/{InvestigationCommandService,InvestigationTaskRunner,InvestigationScheduler,InvestigationEvidenceAssembler}.java`
- Modify: `src/main/java/com/insightflow/{entity/AsyncTask.java,repository/AsyncTaskRepository.java,service/analysis/AlertDetector.java}`
- Test: `src/test/java/com/insightflow/investigation/{InvestigationCommandServiceTest,InvestigationTaskRunnerTest}.java`

**Interfaces:**
- Consumes alert UUID, existing `InvestigationToolService` and `KnowledgeSearchTool`。
- Produces one `investigation` `AsyncTask` and one `InvestigationCase` per alert, both with Workspace-scoped public IDs.

- [ ] **Step 1: Write a failing idempotency test**

```java
@Test
void createsOnlyOneInvestigationTaskForSameAlert() {
    InvestigationCase first = commands.enqueue(workspacePublicId, alertPublicId);
    InvestigationCase second = commands.enqueue(workspacePublicId, alertPublicId);
    assertThat(second.getPublicId()).isEqualTo(first.getPublicId());
}
```

- [ ] **Step 2: Implement schema, `AsyncTask.queuedInvestigation`, and trigger after alert persistence**

```java
public static AsyncTask queuedInvestigation(Long workspaceId, String idempotencyKey, String payloadJson) {
    return queuedWorkspaceTask(workspaceId, idempotencyKey, payloadJson, "investigation");
}
```

- [ ] **Step 3: Implement runner with fixed Tool plan and evidence snapshots, then verify tests**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd -Dtest=InvestigationCommandServiceTest,InvestigationTaskRunnerTest,AlertDetectorTest test`

### Task 4: 人工提案命令、预览、撤销与审计

**Files:**
- Create: `src/main/resources/db/migration/V18__add_action_proposal_schema.sql`
- Create: `src/main/java/com/insightflow/{entity/ActionProposal.java,entity/ActionExecution.java,entity/ProposalAction.java,repository/ActionProposalRepository.java,repository/ActionExecutionRepository.java}`
- Create: `src/main/java/com/insightflow/proposal/{ProposalCommandService,ProposalPreviewService}.java`
- Create: `src/main/java/com/insightflow/controller/InvestigationController.java`
- Test: `src/test/java/com/insightflow/proposal/ProposalCommandServiceTest.java`

**Interfaces:**
- Consumes a `PENDING_REVIEW` investigation and `Idempotency-Key` header.
- Produces `POST /api/v1/workspaces/{workspaceId}/investigations/{caseId}/proposals/{proposalId}/preview`, `/execute`, `/undo`。

- [ ] **Step 1: Write failing role and repeated-key tests**

```java
@Test
void executesProposalOnlyOnceForSameIdempotencyKey() {
    service.execute(workspaceId, caseId, proposalId, "confirm-001");
    service.execute(workspaceId, caseId, proposalId, "confirm-001");
    verify(executionRepository, times(1)).save(any(ActionExecution.class));
}
```

- [ ] **Step 2: Implement guarded command service**

```java
@Transactional
public ActionExecution execute(UUID workspaceId, UUID caseId, UUID proposalId, String key) {
    access.requireRole(workspaceId, MemberRole.OWNER, MemberRole.OPERATOR);
    return existingExecution(workspaceId, key).orElseGet(() -> executeNew(workspaceId, caseId, proposalId, key));
}
```

- [ ] **Step 3: Run proposal/controller tests and verify all outcomes write audit entries**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd -Dtest=ProposalCommandServiceTest,InvestigationControllerTest,AuditLogServiceTest test`

### Task 5: 人工纠错、审核与评测发布门禁

**Files:**
- Create: `src/main/resources/db/migration/V19__add_manual_correction_schema.sql`
- Create: `src/main/java/com/insightflow/{entity/ManualCorrection.java,entity/CorrectionKind.java,repository/ManualCorrectionRepository.java}`
- Create: `src/main/java/com/insightflow/correction/{CorrectionCommandService,CorrectionPublicationService}.java`
- Modify: `src/main/java/com/insightflow/{service/analysis/IssueCatalogService.java,evaluation/EvaluationRegressionGate.java,evaluation/rag/RagLiveEvaluationRunner.java}`
- Modify: `src/main/java/com/insightflow/controller/InvestigationController.java`
- Test: `src/test/java/com/insightflow/correction/CorrectionPublicationServiceTest.java`

**Interfaces:**
- Consumes corrections of `ISSUE_ALIAS`、`RULE_CANDIDATE`、`EVALUATION_CASE`。
- Produces a reviewed publication only when both evaluation gates pass; failures remain reviewable and audited.

- [ ] **Step 1: Write a failing regression-gate test**

```java
@Test
void refusesPublicationWhenEitherEvaluationRegresses() {
    when(goldGate.compare(any(), any())).thenReturn(EvaluationRegressionGate.Result.rejected("fact_coverage"));
    assertThatThrownBy(() -> publication.approve(workspaceId, correctionId))
            .isInstanceOf(EvaluationRegressionException.class);
}
```

- [ ] **Step 2: Implement immutable correction records and Owner approval flow**

```java
public void approve(UUID workspaceId, UUID correctionId) {
    access.requireRole(workspaceId, MemberRole.OWNER);
    runGoldAndRagGates(workspaceId);
    correction.markPublished();
    audit.record(workspaceId, "correction.published", correctionId, "kind=" + correction.getKind());
}
```

- [ ] **Step 3: Verify correction, evaluation and classifier tests**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd -Dtest=CorrectionPublicationServiceTest,EvaluationRegressionGateTest,RagLiveEvaluationRunnerTest,IssueCatalogServiceTest test`

### Task 6: 证据化日报、周报与版本复盘

**Files:**
- Modify: `src/main/java/com/insightflow/{entity/AnalysisReport.java,service/ReportCommandService.java,task/AnalysisReportTaskRunner.java,controller/ReportController.java}`
- Create: `src/main/java/com/insightflow/report/{OperationalReportScope,OperationalReportEvidenceAssembler}.java`
- Create: `src/main/resources/db/migration/V20__add_operational_report_scope.sql`
- Test: `src/test/java/com/insightflow/report/OperationalReportEvidenceAssemblerTest.java`

**Interfaces:**
- Consumes `DAILY`、`WEEKLY`、`VERSION_REVIEW` scope and confirmed investigation cases only.
- Produces existing `AnalysisReport` output with internal investigation/evidence source links.

- [ ] **Step 1: Write a failing scope test**

```java
@Test
void omitsUnconfirmedInvestigationsFromWeeklyReportEvidence() {
    assertThat(assembler.forScope(workspaceId, WEEKLY)).allMatch(item -> item.caseStatus().equals("CONFIRMED"));
}
```

- [ ] **Step 2: Implement report scope validation and controlled evidence assembly**

```java
public List<ReportEvidence> forScope(UUID workspacePublicId, OperationalReportScope scope) {
    return cases.findByWorkspaceAndStatus(workspaceId(workspacePublicId), "CONFIRMED")
            .stream().map(ReportEvidence::from).toList();
}
```

- [ ] **Step 3: Verify report task and API tests**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd -Dtest=OperationalReportEvidenceAssemblerTest,ReportCommandServiceTest,ReportControllerTest,AnalysisReportTaskRunnerTest test`

### Task 7: 调查中心、权限可见性与前端体验收敛

**Files:**
- Create: `frontend/src/views/Investigations.vue`
- Modify: `frontend/src/{App.vue,router/index.js,views/Home.vue,views/Reports.vue,stores/workspace.js}`
- Create: `frontend/test/investigation-runtime-state.test.mjs`
- Modify: `frontend/test/home-runtime-state.test.mjs`, `frontend/package.json`

**Interfaces:**
- Consumes Workspace-scoped investigation, proposal preview/execute/undo, correction and report APIs.
- Produces `/investigations` as调查、处置、纠错与复盘的唯一入口；首页只展示待办摘要。

- [ ] **Step 1: Write failing static runtime tests**

```js
test('investigation view exposes command states and workspace-scoped endpoints', () => {
  assert.match(source, /\/api\/v1\/workspaces\/\$\{store\.workspaceId\}\/investigations/)
  assert.match(source, /proposalRunning/)
  assert.match(source, /权限不足|加载失败|暂无待办/)
})
```

- [ ] **Step 2: Implement the single investigation entry and remove duplicate homepage action controls**

```vue
<router-link to="/investigations" class="btn-primary">处理待办调查</router-link>
```

- [ ] **Step 3: Verify frontend tests and production build**

Run: `npm test; npm run build`

### Task 8: 全量验证、文档更新与提交

**Files:**
- Modify: `docs/agent-optimization-todo.md`, `docs/project-development-log.md`
- Modify: `src/main/resources/static/index.html` and only Vite currently referenced assets

- [ ] **Step 1: Mark P4 work items complete and record verified decisions and UX changes**

- [ ] **Step 2: Run fresh full verification**

Run: `$env:JAVA_TOOL_OPTIONS=$null; .\mvnw.cmd clean test`

Run: `npm test; npm run build`

- [ ] **Step 3: Inspect staged change scope and commit only production code, migrations, frontend delivery assets and documents**

```powershell
git diff --cached --check
git diff --cached --name-only | Select-String -Pattern '(^src/test/|^frontend/test/)' # 预期无输出
git commit -m "feat: 完成 P4 业务闭环与权限协作"
```

## Plan Self-Review

- 覆盖：任务 1/2 对应认证、成员、角色和审计；任务 3/4 对应调查、异步、提案、人工确认、撤销；任务 5 对应纠错与评测；任务 6 对应三类报告；任务 7 对应前端体验审查；任务 8 对应文档、构建和提交。
- 范围：没有包含飞书、钉钉、Jira 或 Agent 写库权限。
- 一致性：所有命令服务统一使用 `WorkspaceAccessService`、UUID 外部标识和 `AuditLogService`；所有异步调查复用 `AsyncTask`。
- 测试：每项生产行为均先写失败测试并运行，再以最小实现转绿；测试文件保留本地、不提交。
