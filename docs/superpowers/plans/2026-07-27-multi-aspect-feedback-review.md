# 多主题评论与人工复核 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不破坏现有主题趋势的前提下，增加主题级情绪和受控人工复核候选。

**Architecture:** 规则分类器继续只产生既有主题键，并扩展为带诊断信息的结果。投影阶段写入主题情绪和候选实体；候选命令服务负责 Workspace 隔离与人工状态转换，前端仅调用受保护 API。

**Tech Stack:** Java 17、Spring Boot、JPA/Flyway、Vue 3、PostgreSQL。

## Global Constraints

- 一条反馈最多两个主题；不新增模型调用、微服务或外部数据源。
- 所有业务读写按 `workspace_id` 隔离，外部 API 只暴露 `public_id`。
- 不保存或展示原始思维链、凭证或未脱敏文本。
- 人工确认不得直接改写规则、历史链接或趋势指标。

---

### Task 1: 分类诊断与主题级情绪

**Files:**
- Create: `src/main/java/com/insightflow/service/analysis/TopicSentiment.java`
- Create: `src/main/java/com/insightflow/service/analysis/TopicSentimentAnalyzer.java`
- Modify: `src/main/java/com/insightflow/service/analysis/RuleFirstIssueClassifier.java`
- Test: `src/test/java/com/insightflow/service/analysis/TopicSentimentAnalyzerTest.java`

- [ ] 写失败测试：混合评论对两个主题得到相反情绪，第三个候选触发上限诊断。
- [ ] 运行测试确认失败。
- [ ] 实现最小主题情绪规则与分类诊断。
- [ ] 运行测试确认通过。

### Task 2: 持久化复核候选与人工状态机

**Files:**
- Create: `src/main/resources/db/migration/V21__add_feedback_review_candidate.sql`
- Create: `src/main/java/com/insightflow/entity/FeedbackReviewCandidate.java`
- Create: `src/main/java/com/insightflow/service/analysis/FeedbackReviewCandidateService.java`
- Test: `src/test/java/com/insightflow/entity/FeedbackReviewCandidateTest.java`

- [ ] 写失败测试：候选只能从 pending_review 转为 confirmed 或 ignored。
- [ ] 运行测试确认失败。
- [ ] 实现实体、迁移和状态机。
- [ ] 运行测试确认通过。

### Task 3: 投影写入与 Workspace API

**Files:**
- Modify: `src/main/java/com/insightflow/service/analysis/WorkspaceProjectionExecutionService.java`
- Modify: `src/main/java/com/insightflow/service/analysis/ProjectionFactWriter.java`
- Create: `src/main/java/com/insightflow/controller/FeedbackReviewController.java`
- Test: `src/test/java/com/insightflow/service/analysis/ProjectionFactWriterTest.java`

- [ ] 写失败测试：超限或混合情绪写入候选，不影响原有最多两主题事实。
- [ ] 运行测试确认失败。
- [ ] 实现投影候选写入、Workspace 隔离查询与确认/忽略命令。
- [ ] 运行相关测试确认通过。

### Task 4: 复核页面、回归与交付

**Files:**
- Modify: `frontend/src/views/Data.vue`
- Modify: `docs/agent-optimization-todo.md`
- Modify: `docs/project-development-log.md`

- [ ] 新增受保护复核队列和人工操作，展示受控摘要而非模型原文。
- [ ] 运行后端全量测试、前端构建与 `git diff --check`。
- [ ] 更新开发记录，提交并推送用户授权的分支。
