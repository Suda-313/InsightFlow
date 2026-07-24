# P2 证据化调查能力实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让单一聊天 Agent 以 Workspace 隔离的只读 Tool 查询舆情数据，并输出可追溯的调查结论。

**Architecture:** 在 `agent.investigation` 中建立意图识别、最少 Tool 规划和只读查询服务；服务输出公开主题标识、受控证据 ID 与脱敏样本。`ChatService` 调用该服务，将计划和证据传入版本化 Prompt，并把证据快照写入既有 `AgentRun`，不暴露数据库主键或原始推理。

**Tech Stack:** Java 17、Spring Boot 3、Spring AI、JPA、PostgreSQL、JUnit 5、Vue 3。

## 全局约束

- 所有查询按 `workspace_id` 隔离；外部输入和输出只使用公开 UUID、主题 key 或受控文本。
- Tool 只读，不执行 SQL 字符串、实体写入或策略变更。
- 每个关键模块使用中文说明设计边界；不保存模型原始思维链。
- 按 TDD 实施，新增能力必须有对应单元测试；完成后运行 Maven 全量测试与前端测试、构建。

---

### Task 1: 受控调查契约与意图规划

**Files:**
- Create: `src/main/java/com/insightflow/agent/investigation/InvestigationIntent.java`
- Create: `src/main/java/com/insightflow/agent/investigation/InvestigationToolType.java`
- Create: `src/main/java/com/insightflow/agent/investigation/InvestigationPlan.java`
- Create: `src/main/java/com/insightflow/agent/investigation/InvestigationIntentDetector.java`
- Create: `src/main/java/com/insightflow/agent/investigation/InvestigationPlanner.java`
- Test: `src/test/java/com/insightflow/agent/investigation/InvestigationPlannerTest.java`

- [ ] 写失败测试，覆盖趋势、异常、环比、版本前后与报告五类意图，以及每类最少 Tool 集合。
- [ ] 运行 `./mvnw.cmd -Dtest=InvestigationPlannerTest test`，确认因类型不存在失败。
- [ ] 实现基于明确中文关键词的确定性识别和不可变计划；未知问题使用最小的主题分布查询。
- [ ] 重跑定向测试，确认通过。

### Task 2: Workspace 隔离的只读数据 Tool 与证据快照

**Files:**
- Create: `src/main/java/com/insightflow/agent/investigation/InvestigationEvidence.java`
- Create: `src/main/java/com/insightflow/agent/investigation/InvestigationResult.java`
- Create: `src/main/java/com/insightflow/agent/investigation/InvestigationToolService.java`
- Modify: `src/main/java/com/insightflow/repository/IssueMetricBucketRepository.java`
- Modify: `src/main/java/com/insightflow/repository/FeedbackEventRepository.java`
- Test: `src/test/java/com/insightflow/agent/investigation/InvestigationToolServiceTest.java`

- [ ] 写失败测试，验证趋势、主题分布、告警、样本、时间范围比较均只使用当前工作区数据，样本数量和长度受限。
- [ ] 运行定向测试，确认服务不存在而失败。
- [ ] 实现受控查询：只接收用户问题和工作区 UUID，不接受 SQL、内部 ID 或任意字段；版本比较在不存在版本来源时返回明确的数据不足证据。
- [ ] 重跑定向测试，确认通过。

### Task 3: 接入聊天、Prompt 与 AgentRun 证据审计

**Files:**
- Modify: `src/main/java/com/insightflow/prompt/ChatPromptTemplate.java`
- Modify: `src/main/java/com/insightflow/service/ChatService.java`
- Modify: `src/test/java/com/insightflow/service/ChatServiceTest.java`
- Modify: `src/test/java/com/insightflow/prompt/ChatPromptTemplateTest.java`

- [ ] 写失败测试，要求聊天在模型调用前获得调查结果，并在 `AgentRun.Completion.evidenceJson` 中保存 Tool 名与证据 ID。
- [ ] 运行定向测试，确认旧的静态上下文实现无法满足断言。
- [ ] 将 Prompt 升级为新版本，强制输出“结论、证据、推测、未知项、建议动作”，数字、时间和因果判断须引用证据或说明不足。
- [ ] 重跑定向测试，确认成功、失败路径均保持 Trace 收敛。

### Task 4: P2 评测、前端呈现与文档

**Files:**
- Modify: `src/main/java/com/insightflow/evaluation/*`
- Modify: `src/main/resources/evaluation/gold-evaluation-cases.json`
- Modify: `frontend/src/views/Home.vue`
- Modify: `frontend/test/home-runtime-state.test.mjs`
- Modify: `docs/agent-optimization-todo.md`
- Modify: `docs/project-development-log.md`

- [ ] 为金标题目增加证据引用期望，评测运行器统计引用覆盖率并将缺少证据的答案视为回归。
- [ ] 在聊天页显示本次回答的 Trace 与可读证据索引，不显示原始思维链。
- [ ] 更新 P2 Todo 和开发记录，只记录已验证的设计取舍与结果。
- [ ] 运行 `./mvnw.cmd test`、`npm test`、`npm run build` 与 `git diff --check`。
