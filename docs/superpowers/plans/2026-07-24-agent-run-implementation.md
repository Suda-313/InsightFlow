# AgentRun 运行记录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为聊天 Agent 落地可按工作区查询的 AgentRun 审计记录。

**Architecture:** 以 `AgentRunService` 管理运行生命周期，`ChatService` 显式调用该服务。`agent_run` 同时保存 `workspace_id` 与 UUIDv7 Trace，Controller 只读公开字段。

**Tech Stack:** Java 17、Spring Boot、Spring Data JPA、Flyway、Spring AI、JUnit 5、Mockito。

## Global Constraints

- 模块化单体；不引入新的微服务、向量库或多 Agent 编排。
- 所有读写必须按 `workspace_id` 隔离；HTTP 只暴露 UUIDv7。
- 不保存或展示模型思维链、异常堆栈和完整系统 Prompt。
- 关键模块、实体、迁移与 API 使用中文业务注释。

---

### Task 1: AgentRun 持久化模型与隔离服务

**Files:**
- Create: `src/main/java/com/insightflow/entity/AgentRun.java`
- Create: `src/main/java/com/insightflow/repository/AgentRunRepository.java`
- Create: `src/main/java/com/insightflow/service/AgentRunService.java`
- Create: `src/main/resources/db/migration/V10__add_agent_run_schema.sql`
- Test: `src/test/java/com/insightflow/service/AgentRunServiceTest.java`

- [ ] 写失败测试：创建记录绑定可信工作区；跨工作区查询 Trace 失败；成功记录 Token 与最终输出；失败记录固定错误码。
- [ ] 运行 `mvnw.cmd test -Dtest=AgentRunServiceTest`，确认因类型不存在而失败。
- [ ] 实现实体、仓储、迁移和服务最小生命周期接口。
- [ ] 再次运行目标测试，确认通过。

### Task 2: 聊天 Agent 接入与只读 API

**Files:**
- Modify: `src/main/java/com/insightflow/service/ChatService.java`
- Create: `src/main/java/com/insightflow/controller/AgentRunController.java`
- Test: `src/test/java/com/insightflow/service/ChatServiceTest.java`
- Test: `src/test/java/com/insightflow/controller/AgentRunControllerTest.java`

- [ ] 写失败测试：聊天成功完成 AgentRun；模型异常失败 AgentRun；Controller 返回公开 Trace 与工作区范围结果。
- [ ] 运行目标测试，确认新接口未实现导致失败。
- [ ] 接入 PII 脱敏摘要、模型名称、Usage、成功与失败生命周期；新增只读 Controller。
- [ ] 运行目标测试，确认通过。

### Task 3: 迁移契约、文档与全量验证

**Files:**
- Modify: `src/test/java/com/insightflow/entity/ProjectionSchemaMigrationTest.java`
- Modify: `docs/agent-optimization-todo.md`
- Modify: `docs/project-development-log.md`

- [ ] 写 V10 结构契约测试并确认初始失败。
- [ ] 补齐迁移约束与索引后确认通过。
- [ ] 记录 AgentRun 设计取舍，将 Todo 的首项标记为完成。
- [ ] 运行 `mvnw.cmd test`、`npm test`、`npm run build` 与 `git diff --check`。
