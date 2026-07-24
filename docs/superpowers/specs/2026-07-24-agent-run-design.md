# AgentRun 运行记录设计

## 目标

为 InsightFlow 的模型调用建立可查询、可按工作区隔离的审计记录。首版先接入聊天 Agent；分析类与报告类 Agent 后续复用相同服务，不复制表结构或鉴权逻辑。

## 范围与取舍

- 记录每次聊天模型调用的生命周期：`running`、`succeeded`、`failed`。
- 记录 Agent 类型、Prompt 版本、模型、检索版本、脱敏输入摘要、最终输出、Token、耗时、错误码、Trace 标识和时间。
- 提供工作区范围内的运行列表和单条详情查询。
- 不记录模型原始思维链、完整系统 Prompt、异常堆栈、工具调试参数或数据库内部主键。
- 首版不接入分类、情感、风险和报告 Agent；它们没有统一的工作区入口，直接接入会把异步投影链路与聊天改动耦合。
- 首版不做评测运行器、Prompt 管理 UI 或成本看板；这些能力以 AgentRun 为事实来源在后续 P1 事项实现。

## 数据模型

`agent_run` 使用内部 `id` 关联数据库，使用 UUIDv7 `public_id` 作为 API 的 `trace_id`。所有查询均带 `workspace_id`。

| 字段 | 含义 |
|---|---|
| `agent_type` | 如 `chat`，用于后续按 Agent 维度统计 |
| `status` | `running` / `succeeded` / `failed` |
| `prompt_version` | 例如 `chat:v1`，而不是复制系统 Prompt 原文 |
| `model_name` | 当前调用的模型配置 |
| `retrieval_version` | 聊天首版为 `none`，为 RAG 预留 |
| `input_summary` | 经 PII 脱敏并截断的用户问题摘要 |
| `output_text` | 模型最终答案，不含推理链 |
| `evidence_json` | 当前聊天未调用结构化 Tool，初始为 `null` |
| `prompt_tokens` / `completion_tokens` / `total_tokens` | 模型返回时记录，缺失时为 `null` |
| `latency_ms` | 调用完成或失败时的耗时 |
| `error_code` | 固定业务码，不保存异常正文 |

## 数据流

1. `ChatService` 在调用模型前通过 `AgentRunService.start` 创建 `running` 记录。
2. 成功时从 `ChatResponse` 提取 Usage，保存最终回答与耗时，再保存会话助手消息。
3. 失败时将运行记录更新为 `failed` 和 `MODEL_CALL_FAILED`，保留 Trace 标识供日志关联，然后继续抛出原异常给既有 API 错误处理。
4. `AgentRunController` 只按工作区与公开 Trace 标识读取，不暴露内部主键。

## 安全与边界

- 所有服务方法先解析 `workspace_id`，跨工作区 Trace 一律返回 404。
- 输入摘要使用现有 `PiiSanitizer`，并限制为 500 个字符；它用于定位请求，不作为完整对话备份。
- 输出为已对用户展示的最终答案；后续若需额外脱敏，应在模型输出进入会话和 AgentRun 前统一处理。
- 运行记录写入失败不应掩盖模型调用结果；但运行生命周期更新失败会记录服务端日志并保留原业务结果。

## 验证

- 单元测试覆盖创建、成功、失败、工作区隔离和 Token 缺失。
- 聊天服务测试验证成功与失败均调用 AgentRun 生命周期服务。
- Controller 测试验证公开 Trace 响应，不泄露内部键。
- Flyway 契约测试验证表、状态约束和隔离索引。
