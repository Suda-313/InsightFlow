# P3 企业知识库与受控 Agentic RAG 设计

## 目标

为 InsightFlow 增加组织级、可治理、可追溯的 Markdown/TXT 企业知识库。聊天 Agent 在当前游戏 Workspace 的舆情证据之外，可按受控流程检索组织通用和当前游戏专属知识，并在回答中引用稳定的文档片段与来源链接。

本阶段完成 P3，不实现登录、用户、成员、角色、SSO、跨组织协作或 P4 的写操作工作流。

## 领域边界

`Workspace` 继续表示一个游戏、产品线或独立舆情分析对象。它是导入文件、反馈、主题、指标、告警、报告和 AgentRun 的数据上下文，不能重定义为用户对话窗口。

`ChatSession` 表示类似豆包的独立对话窗口。同一 Workspace 可以有多个 ChatSession；它不是权限实体。

P3 引入轻量 `Organization` 作为 Workspace 和知识文档的共同归属。当前没有用户体系，因此不在 P3 做成员和角色校验；P4 再以 `organization_member`、`workspace_member` 和角色表补齐访问控制。

## 知识治理模型

### 文档与范围

知识文档只有四种类型：`RELEASE_NOTE`、`KNOWN_ISSUE`、`SUPPORT_SOP`、`SENTIMENT_PLAYBOOK`。

每个 `KnowledgeDocument` 归属一个 Organization，并有可空 `target_workspace_id`：

- 空值表示组织通用文档，当前组织内任一 Workspace 的 RAG 都可检索。
- 非空值表示游戏专属文档，只有该 Workspace 的 RAG 可检索。

首版不支持一篇文档授权给多个指定 Workspace。多游戏通用资料维护为组织通用文档；确有不同游戏版本的内容时创建各自游戏专属文档。这样避免过早引入授权关联表。

### 生命周期与版本

文档版本状态固定为 `PENDING_REVIEW`、`PUBLISHED`、`EXPIRED`、`DELETED`。

上传 Markdown/TXT 时创建新的待审批版本，原文件存 MinIO，数据库只保存对象键、校验和、来源显示名与元数据。审批发布新版本时，同一文档此前已发布版本自动变为已失效；失效或删除版本永不参与检索，但保留版本和引用审计。

每个版本发布时产生切片、全文索引和向量。版本号从 1 单调递增，不能覆写既有版本。

## 数据模型

| 表 | 核心字段 | 约束 |
|---|---|---|
| `organization` | 内部 id、public_id、name、created_at | `public_id` 对外使用；P3 只提供归属，不提供成员关系 |
| `workspace` | 新增 `organization_id` | 所有已有 Workspace 与 P3 阶段新建的 Workspace 均归属系统默认 Organization；P4 开放组织管理后，新建时再显式选择 Organization |
| `knowledge_document` | id、public_id、organization_id、target_workspace_id、type、title、created_at | 目标 Workspace 非空时必须属于同一 Organization |
| `knowledge_document_version` | id、public_id、document_id、version_no、status、object_key、checksum、source_name、published_at、expired_at | `(document_id, version_no)` 唯一；发布状态同一文档最多一个 |
| `knowledge_chunk` | id、public_id、version_id、chunk_no、content、content_tsv、embedding vector、token_count | `(version_id, chunk_no)` 唯一；只允许已发布版本的切片被检索 |

PostgreSQL 启用 `vector` 扩展，向量列固定为与配置的 embedding 模型一致的维度。Flyway 在创建 `ivfflat` 向量索引前检查扩展存在；全文索引使用 `GIN`。不新增独立向量数据库。

## 受控 Agentic RAG

### 调用流程

1. 聊天用例先按当前 Workspace 取得 Organization。
2. `KnowledgeRetrievalPlanner` 根据问题和当前意图判断是否需要知识检索，识别优先文档类型和版本线索。
3. `KnowledgeSearchTool` 仅查询当前 Organization 的组织通用文档和当前 Workspace 专属文档，且仅限已发布、未失效版本。
4. 首次检索使用元数据过滤、PostgreSQL 全文检索与 pgvector 相似度检索；通过固定 Reciprocal Rank Fusion 合并结果。
5. `KnowledgeEvidenceGuardrail` 判断首轮是否具备足够证据。若没有命中、命中类型不匹配或得分低于固定阈值，只允许执行一次补检索：放宽类型过滤或改为版本/已知问题精确检索。
6. 最多两轮的证据写入 Prompt，模型只能生成最终回答；模型不能生成 SQL、调用仓储、修改文档或继续循环。
7. AgentRun 保存检索策略版本、计划、轮次、文档 public_id、版本号、切片 public_id 与融合分数；不保存思维链。

### 引用契约

每项 RAG 证据使用稳定 ID，例如 `knowledge:<document-public-id>:v<version>:<chunk-public-id>`。回答的数字、时间和知识性断言继续使用现有 `[证据: evidence-id]` 格式。

Chat API 的安全证据列表新增文档标题、版本号、截断片段与应用内来源链接；不得返回 MinIO 私有凭据、内部数据库主键、完整原文件或未发布内容。

无可用知识依据时，Agent 明确说明未检索到已发布知识，并区分“当前舆情数据事实”和“缺少企业知识依据”。

## Embedding 与异步边界

复用当前 DashScope OpenAI 兼容配置，新增单独的 embedding 模型配置项，默认使用 `text-embedding-v3`。模型密钥缺失或 embedding 调用失败时，待审批版本不能发布；不会伪造向量或让半成品切片进入检索。

首版上传和审批接口同步完成小文件 Markdown/TXT 的读取、切片和向量化。文件大小沿用 Spring multipart 限制；超出限制或内容为空直接拒绝。后续大文件批量嵌入可复用已有持久任务机制，但不提前实现。

## API

所有知识 API 位于 `/api/v1/workspaces/{workspaceId}/knowledge`，路径 Workspace 只用于解析 Organization 和当前游戏范围：

- `POST /documents`：上传 Markdown/TXT，创建待审批文档版本。
- `GET /documents`：按当前可见范围列出文档与版本状态。
- `POST /documents/{documentId}/versions/{versionId}/publish`：发布待审批版本，并使旧发布版本失效。
- `POST /documents/{documentId}/versions/{versionId}/expire`：使发布版本失效。
- `DELETE /documents/{documentId}/versions/{versionId}`：逻辑删除版本。
- `GET /documents/{documentId}/versions/{versionId}/source`：返回已授权版本的原文件下载或展示入口。

当前没有用户权限体系，因此 P3 API 不伪装为角色鉴权；所有范围判断仍通过 Workspace → Organization 与文档范围进行。

## 专项评测

新增隔离的 RAG 金标集，覆盖版本公告、已知问题、SOP、舆情处置、无依据问题五类。每题定义：可见 Workspace、固定知识 fixture、期望命中文档/版本/片段、禁止引用项和无依据预期。

评测输出：

- `retrievalRecallRate`：期望片段是否被召回；
- `citationCorrectnessRate`：最终回答的知识引用是否属于期望文档/片段；
- `ungroundedAnswerRate`：没有知识依据时仍作知识性断言的比例，越低越好；
- 保留 P1 的事实覆盖、禁止断言、拒答、耗时和 Token 指标。

Prompt、embedding 模型、检索策略或切片规则改变后，使用同一 RAG 金标集运行并与历史批次比较。

## 验收与测试

1. 不同 Organization 的知识绝不互相检索；同 Organization 的组织通用文档可被任一 Workspace 检索。
2. 游戏 A 的专属版本公告不能被游戏 B 的检索命中。
3. 待审批、失效和删除版本不能被检索；发布新版本后旧版本失效且历史引用可读。
4. 版本公告问题可引用正确的文档、版本、片段和来源链接；无依据问题明确拒绝知识性断言。
5. 首轮不足时仅发生一次补检索；不会出现第三轮或模型自由查询。
6. Flyway、服务层、Controller、聊天链路、评测和前端均有对应自动化测试；后端全量测试与前端测试、构建通过。

## 非目标

- PDF、Word、OCR、网页抓取和外部爬虫；
- 登录、用户、成员、组织角色、Workspace 角色、SSO；
- 多 Agent 协作、无限 ReAct 循环、模型直连数据库；
- 一篇文档面向多个指定 Workspace 的细粒度授权；
- P4 的告警处置、飞书/钉钉/Jira 推送。
