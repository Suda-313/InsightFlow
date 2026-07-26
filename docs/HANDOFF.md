# InsightFlow 开发交接

> 最后更新：2026-07-26
>
> 当前分支：`feature/data-cell-rule-issue-merging`
>
> 远程仓库：`https://github.com/Suda-313/yuqiagent.git`

## 先说结论

可以切换到另一台电脑继续开发，但**当前工作区有大量未提交改动和未跟踪文件**。另一台电脑仅拉取当前分支，无法获得这些改动、知识库样例、前端构建产物或本地数据库数据。

切换前应二选一：

1. 先审查并提交、推送本次确认要保留的代码与文档；此前约定的测试类不提交，应继续遵守。
2. 若暂不提交，则完整、私密地复制当前工作目录和本地运行配置到新电脑；不要把密钥写入 Git 或交接文档。

不建议直接在新电脑重新开始：当前未提交内容混有多个阶段的改动，容易遗漏。

## 当前目标与卡点

当前正在收口 RAG 评测链路，目标是建立一份可复跑、可比较的真实 RAG 质量基线。

### 已完成

- RAG 长评测已改为异步任务：创建接口返回 `202 + task_id`，前端轮询任务状态，避免 HTTP 请求超时。
- 每道 RAG 用例均输出 `RAG_EVAL` 日志，包含用例 ID、执行状态、失败阶段、检索/生成/总耗时；无法从现有 Spring AI 网关取得 token 时明确记录 `unavailable`，不伪造指标。
- 增加 50 秒模型 HTTP 读取超时和 55 秒单用例评测超时，防止后台任务无限占用线程。
- 评测任务只有在**全部用例成功**时才会保存为质量基线；任一用例失败即为 `partial_failed`，不生成可比较的 `run_id`。
- 修正了生成阶段失败的耗时归因，避免将模型生成耗时错误记为检索耗时。
- 已补充相关开发记录与 Todo：`docs/project-development-log.md`、`docs/agent-optimization-todo.md`。

### 当前外部卡点

真实模型调用仍不稳定：部分 RAG 用例在约 50 秒后于 `generation_failed` 结束。最近一次真实任务已正确返回 `partial_failed`，且没有写入新的质量基线。

这不是“评测代码已通过”的结论，而是当前保护逻辑生效的证明。尚不能使用历史 RAG 指标做模型、Prompt 或检索策略的优劣比较。

另有一份早期历史 RAG 记录是在“部分用例成功即可保存”旧逻辑下生成的，其中多道用例失败；它仅可作为排障证据，**不能作为质量基线**，未经确认不要删除。

## 下一步建议（按优先级）

1. 启动本地服务后再次触发 RAG 评测，观察每道 `RAG_EVAL` 日志，确认失败集中在检索、重排还是模型生成阶段。
2. 优先排查百炼/DashScope 的模型响应：模型选择、网络连通性、服务端限流和生成长度。不要为了“跑通”而静默缩短答案、删除用例或放宽成功标准。
3. 当 5 道用例全部 `succeeded` 后，保存该次结果为第一份有效 RAG 基线；再以相同数据集和同一配置比较 Prompt、检索和后续向量库改动。
4. 有了有效基线后，再继续 Todo 中的 Agent 只读工具、组织级知识库与工作区范围检索等能力；写操作仍必须人工确认。

## 本地恢复清单

### 代码与依赖

1. 安装 JDK 17、Docker Desktop、Git；Maven 使用项目自带的 `mvnw.cmd`。
2. 获取已同步的代码后切换到 `feature/data-cell-rule-issue-merging`。
3. 运行 `docker compose up -d` 启动 PostgreSQL、MinIO 等依赖。
4. 按项目的本地配置示例重新设置 API Key、JWT 密钥和本地存储配置；密钥只通过环境变量或受控本地配置提供。
5. 启动后访问 `http://localhost:8080/actuator/health`。

### 数据与知识库

- 当前本地数据库中的工作区、导入评论、对话、评测历史和知识库索引不会随 Git 同步。若要保留现状，需要迁移本地数据库/卷；若只需重新验证功能，可在新电脑重新导入 CSV 并重新上传知识库文档。
- 知识库样例与来源材料位于 `docs/knowledge-sources/`，属于当前未跟踪文件，切换前需确认是否随代码一起保留。
- 不迁移本地数据时，不应期待新电脑能看到旧工作区、旧 RAG 任务或旧对话记录。

## 关键实现位置

- RAG 异步任务与严格成功判定：`src/main/java/com/insightflow/evaluation/rag/RagEvaluationTaskRunner.java`
- 单用例超时与耗时归因：`src/main/java/com/insightflow/evaluation/rag/RagEvaluationCaseExecutor.java`
- 逐题日志与指标汇总：`src/main/java/com/insightflow/evaluation/rag/RagLiveEvaluationRunner.java`
- 任务创建、轮询接口：`src/main/java/com/insightflow/controller/EvaluationController.java`
- 模型 HTTP 超时：`src/main/java/com/insightflow/config/AgentConfiguration.java`
- 配置项：`src/main/resources/application.yml`
- RAG 后续事项：`docs/agent-optimization-todo.md`
- 关键决策与验证记录：`docs/project-development-log.md`

## 已验证范围

以下命令在本机最近一次执行通过：

```powershell
.\mvnw.cmd '-Dtest=AgentConfigurationTest,EvaluationControllerTest,RagEvaluationControllerTest,RagEvaluationTaskCommandServiceTest,RagEvaluationTaskQueryServiceTest,RagEvaluationTaskRunnerTest,RagEvaluationCaseExecutorTest,RagLiveEvaluationRunnerTest' test
npm --prefix frontend run build
.\mvnw.cmd -DskipTests package
git diff --check
```

说明：未执行全量 Maven 测试；前端构建存在既有的 Vite CSS `@import` 顺序警告，构建本身成功。

## 不可突破的约束

- 遵循 `AGENTS.md`：KISS/YAGNI、默认外科手术式修改，跨文件设计先说明取舍再实施。
- 外部 API 仅暴露 `public_id`；业务读写按 `workspace_id` 隔离；Agent 当前仅做只读调查，策略或数据写入必须人工确认。
- 不保存或展示模型原始思维链；只保存用户消息、最终回答、可核验依据与必要的调用指标。
- 不将 API Key、密码、Token、个人信息或未脱敏业务数据写入源码、测试、日志或文档。
- 不执行 `git reset --hard`，不擅自清理当前未提交文件。
