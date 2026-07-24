# 聊天 LLM 指标日志设计

## 目标

让每次聊天模型调用在后端控制台输出可关联的耗时和 Token 用量，方便开发阶段排障与成本观察。

## 方案

- 复用现有 `LlmMetrics` 的耗时与 Usage 提取逻辑。
- 为聊天链路增加一个带 `trace_id` 的成功日志方法，输出 Agent 类型、服务端耗时、输入 Token、输出 Token 和总 Token。
- 在 `ChatService` 成功持久化 `AgentRun` 后调用该方法；失败时输出 `trace_id`、固定状态和耗时。

## 日志边界

- 不记录用户消息、历史对话、系统 Prompt、模型回复正文、API Key 或异常堆栈中的敏感请求内容。
- Token 由模型响应 `Usage` 提供；供应商未返回时明确输出“token 信息不可用”，不以字符数伪造。
- `latency_ms` 沿用当前 `AgentRun` 的服务端耗时定义，不包含前端网络和渲染时间。

## 验收

1. 聊天成功时控制台出现包含 `trace_id`、耗时和 Token 的单条 INFO 日志。
2. 模型调用失败时控制台出现包含 `trace_id` 与耗时的 WARN 日志。
3. 日志内容不含用户问题、Prompt、回答正文或密钥。
4. 原有分析 Agent 的 `LlmMetrics` 日志格式保持兼容。
