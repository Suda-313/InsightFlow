# InsightFlow 项目开发记录

> 最后更新：2026-07-30
>
> 本文档是项目的长期开发记录，用于沉淀业务背景、架构取舍、关键问题和验证结果。它面向后续开发、简历项目说明和技术面试；只记录已经在代码、测试或运行环境中得到验证的事实，待完成事项明确标记为待办。

## 1. 业务背景与项目目标

游戏客服反馈会持续产生大量非结构化文本。依靠人工翻阅工单，很难及时发现玩法 Bug、登录异常、支付问题等主题的增长趋势，也难以把告警、样本反馈和运营行动串成可复盘的调查过程。

InsightFlow 的目标是将 CSV 反馈数据转化为可调查的舆情信号：

1. 导入并脱敏反馈数据，保证后续分析不依赖原始敏感内容。
2. 用确定性规则完成主题归类、时间窗口聚合、基线计算和异常告警。
3. 使用 Agent 对已有分析事实进行只读调查和报告生成，而不是让模型直接修改业务数据。
4. 为运营人员提供主题、趋势、告警和报告入口，关键决策仍由人工确认。

项目边界：当前不引入微服务、真实爬虫或未经确认的数据源；Agent 不拥有写入告警、调整策略或修改数据的权限。

## 2. 技术架构与关键选型

| 决策 | 选择 | 原因与取舍 |
|---|---|---|
| 应用架构 | Java + Spring Boot 模块化单体 | 当前业务以导入、分析、任务和 API 为主，模块化单体能保持事务边界、调试效率和部署复杂度可控；不为当前规模提前拆分微服务。 |
| 分析策略 | 规则优先，LLM 增强 | 主题分类、指标、EWMA 和告警使用确定性逻辑，保证结果可复现；LLM 只负责补充分析和生成报告，避免模型成为统计事实的唯一来源。 |
| 异步处理 | 数据库持久化任务 + 租约 | CSV 导入、数据投影和报告生成耗时较长，任务状态、租约与重试需要跨进程可恢复，不能只依赖内存线程池。 |
| 数据存储 | PostgreSQL + MinIO + Redis | PostgreSQL 保存按 Workspace 隔离的领域数据和任务状态；MinIO 保存原始导入对象；Redis 预留给缓存与协调，不替代核心事实数据。 |
| Agent 集成 | Spring AI + 受控服务边界 | 保持模型调用与业务数据查询在 Java 服务层内，便于记录成本、限制权限、增加 Tool 和 Trace。 |
| 知识库演进 | 先 PostgreSQL，后 pgvector | 当前已有知识条目模型；在建立评测与引用机制前，不提前引入独立向量数据库，降低系统复杂度。 |

## 3. 已实现能力概览

| 能力 | 当前实现 | 业务价值 |
|---|---|---|
| CSV 导入与脱敏 | 上传、字段映射、异步导入和敏感信息处理 | 将非结构化反馈转为可分析数据，同时降低敏感数据风险。 |
| 数据投影与主题分析 | DataCell 切分、规则优先主题分类、指标桶聚合 | 形成按主题和时间维度可复用的分析事实。 |
| 异常检测 | EWMA 基线、z-score 告警和冷却窗口 | 将“工单多了”的主观感受变为可量化的异常信号。 |
| Agent 增强 | 分类、情感、风险分析与报告生成 | 为确定性分析补充解释、摘要和运营报告。 |
| 看板与报告 | Dashboard、主题详情、告警历史和报告下载 | 提供运营查看和复盘入口。 |
| 前端聊天 | Workspace 范围内的 AI 问答、会话恢复和短期记忆 | 刷新后恢复最近活动会话；模型基于最近 12 条最终消息理解追问。 |

## 4. 关键问题与解决记录

### 2026-07-25：企业知识库与受控 Agentic RAG（P3）

- **背景或现象：** 舆情分析助手只能依据当前数据调查回答，无法可靠引用版本公告、已知问题、客服 SOP 和舆情处置手册；同时需要为后续多游戏企业场景保留组织共享知识和游戏专属知识的边界。
- **根因：** 原有知识条目缺少组织归属、不可覆盖版本、发布状态、混合检索、应用内引用和专项评测闭环，模型若直接访问数据库也会破坏 Workspace 隔离和审计边界。
- **方案与取舍：** 保持模块化单体，P3 仅引入 Organization 作为归属边界，所有 Workspace 先绑定默认组织；不提前实现用户、成员和角色。原文放入 MinIO，元数据、版本、切片和向量放入 PostgreSQL + pgvector；不用独立向量数据库、PDF/OCR、自由 ReAct 或多 Agent。
- **实现：** 新增 V12/V13/V14 迁移、组织和受治理知识模型；文档可见范围为“组织通用”或“当前 Workspace 专属”。检索固定执行组织/Workspace/已发布状态过滤、全文检索、向量召回和 RRF 融合，最多两轮。聊天提示词将知识片段视为不可信资料，要求知识断言使用 `knowledge:` 证据引用；AgentRun 只保存计划、检索轮次和证据快照。RAG 评测题从当前可见的已发布文档生成，独立保存三项指标与脱敏逐题计数。
- **验证：** 已新增并执行知识上传、发布状态机、检索样本边界、聊天知识证据、RAG 指标、RAG 实际运行、RAG 历史和 API、前端 RAG 评测区块的回归测试；最终以 `mvnw.cmd clean test`、`frontend/npm test` 与 `frontend/npm run build` 作为交付验证命令。
- **沉淀：** Workspace 是游戏/产品的数据与分析边界，ChatSession 才是一个对话窗口；没有成员权限前不把 Workspace 伪装为用户私有空间。组织权限、写操作和协作通知留待 P4，并继续要求人工确认、确定性 Command Service 和审计记录。

### 2026-07-25：P4 身份权限与调查处置闭环

- **背景或现象：** 原系统能够发现告警并生成只读分析，但缺少登录身份、组织/Workspace 范围、人工确认、撤销、审计和纠错发布门禁，无法安全承载真实运营处置。
- **方案与取舍：** 保持模块化单体，采用本地 BCrypt 账号和短期 JWT；组织 Owner 可查看本组织 Workspace，其他成员必须显式加入 Workspace。告警仍是不可变触发事实，异步调查只冻结证据和生成固定提案；Agent 不获得直接写库能力。没有接入版本/活动事件源时，版本复盘报告显式提示不能推断因果。
- **实现：** 新增成员关系、审计、调查卡片、证据快照、提案执行/撤销、人工纠错和报告范围迁移；所有对外接口只使用 UUID。调查中心统一展示证据、预览、人工执行、撤销和纠错候选；首页移除与调查中心重复的处置按钮。报告支持日报、周报和版本复盘范围，且正式证据仅来自已确认调查。
- **验证：** `mvnw.cmd clean test` 通过 148 项测试；前端 `npm test` 通过 12 项、`npm run build` 成功。构建保留既有 CSS `@import` 顺序警告，不影响产物生成。
- **沉淀：** 将“模型建议”与“业务状态变更”拆开，能使智能能力可扩展而不降低可控性；撤销也必须保留执行事实并恢复原提案可复核状态，不能靠删除记录实现。

### 2026-07-24：配置文件中存在默认模型服务密钥

- **现象：** 后端配置为模型服务密钥提供了明文默认值，源码泄露或配置被复制时会扩大密钥暴露范围。
- **根因：** 初期为降低本地启动门槛，将实际密钥写入 Spring 配置的占位符默认值。
- **方案与取舍：** 将配置改为仅从 `DASHSCOPE_API_KEY` 环境变量读取，未设置时使用空值。这样基础分析功能可继续在本地运行，但调用 Agent 前必须由部署环境显式提供密钥；不为了方便继续保留源码默认密钥。
- **实现：** 修改 `src/main/resources/application.yml`，并将对应安全事项标记为已完成。
- **验证：** 在未设置 `DASHSCOPE_API_KEY` 的环境运行后端测试，Spring 测试上下文能够启动，未出现占位符解析失败；配置搜索确认源码资源中不再包含默认密钥。全量测试仍有既有失败，原因是 Dashboard 测试使用旧构造函数和旧 DTO 签名，以及两处 Agent Mock 未返回预期输出，与本次配置变更无关。
- **沉淀：** 密钥不能作为配置默认值、示例值或日志内容；文档只使用 `sk-xxx` 等脱敏占位符。

### 2026-07-24：空模型密钥导致 ApplicationContext 启动失败

- **现象：** 未设置 `DASHSCOPE_API_KEY` 时，后端启动在 `AgentAnalysisScheduler → CellAnalysisAgent → ClassificationAnalyzer → ChatClient → OpenAiChatModel` 链路失败，并抛出 `OpenAI API key must be set`；基础导入和数据分析功能也无法使用。
- **根因：** `AgentConfiguration` 无条件创建 `ChatClient`，使可选的 Agent 运行时变成了 Spring 容器的必选依赖。进一步验证发现 Spring AI 1.1.0 的 `OpenAiAutoConfiguration` 默认还会创建项目未使用的音频模型，因此只给聊天模型加条件不足以解决启动问题。
- **方案与取舍：** 默认关闭 `AGENT_ENABLED`，并以“显式启用 Agent + 非空密钥”作为所有模型依赖组件的共同装配条件；排除 Spring AI 的 OpenAI 自动配置，改由 `AgentConfiguration` 只创建项目实际使用的聊天模型。没有保留空密钥、伪造模型或忽略异常的兼容层，避免应用看似启动成功却在首次聊天时才暴露配置错误。
- **实现：** 新增 `AgentApiKeyPresentCondition`，应用到聊天、分析 Agent、报告 Agent 和调度器；`AgentConfiguration` 显式构造 DashScope 兼容的 `OpenAiChatModel` 与 `ChatClient`；`application.yml` 排除未使用模型的自动装配，并将 `insightflow.agent.enabled` 默认改为 `false`。
- **启用方式：** 需要模型能力时，在启动环境同时设置 `AGENT_ENABLED=true` 与 `DASHSCOPE_API_KEY=<你的密钥>`；只体验导入、投影、看板和规则分析时不需要密钥。
- **验证：** `AgentConfigurationTest` 覆盖空密钥不创建 `ChatClient`、启用且有密钥时创建聊天客户端两条路径；`./mvnw.cmd test` 已通过，结果为 90 个测试通过、0 个失败、0 个错误。
- **沉淀：** 第三方 Starter 的自动配置范围必须在实际 Bean 创建层验证，不能仅根据“项目未调用某功能”推断它不会被装配；可选集成应有显式开关和可重复的无依赖启动测试。

### 2026-07-24：本地启动重复配置 Agent 环境变量

- **现象：** 开发者每次从 IDE 启动类运行后端时，都需要重复配置 `AGENT_ENABLED` 和 `DASHSCOPE_API_KEY`，否则模型能力不会启用。
- **根因：** 密钥已从受版本控制的主配置中移除以避免泄露，但项目没有提供 Spring Boot 原生的本机私有配置入口。
- **方案与取舍：** 将 `local` 设为默认 Profile，约定开发机使用 `application-local.yml` 保存一次性配置；该文件被 Git 忽略，仓库仅保留不含密钥的示例文件。没有引入 dotenv 依赖或将密钥放回 `application.yml`，避免为单机开发便利增加依赖或重新扩大凭据暴露面。
- **实现：** `application.yml` 新增默认 Profile，`.gitignore` 排除 `src/main/resources/application-local.yml`，新增 `application-local.yml.example`；开发者首次复制示例文件并填入密钥后，启动类会自动加载该配置。
- **验证：** `LocalAgentProfileConfigurationTest` 覆盖本机文件缺失时仍使用 `local` 默认 Profile 且 Agent 保持安全关闭；全量 Maven 测试结果见本次开发记录后的验证命令。
- **沉淀：** 本地便利配置应采用“可复制示例 + 被忽略的真实文件”模式；示例可指导配置但绝不能包含真实凭据。

### 2026-07-24：后端测试基线与生产契约失配

- **现象：** 全量后端测试中，Dashboard 测试因构造函数和响应 DTO 签名不匹配而失败；分类和报告 Agent 测试返回空结果。
- **根因：** 生产 Dashboard 已增加样本反馈依赖并扩展公开 DTO，但测试仍使用旧构造函数、旧路由和旧响应形状；分类测试使用 Java 字段名而非模型 JSON 的 `canonical_key`；报告测试 mock 了字符串消息重载，而生产代码使用 Consumer 形式构造用户消息。
- **方案与取舍：** 仅更新测试以匹配当前生产契约，不回退生产 DTO，也不为了旧测试增加兼容构造函数。这样测试继续验证真实 API 和模型调用链。
- **实现：** 同步 `DashboardServiceTest`、`DashboardControllerTest`、`ClassificationAnalyzerTest` 与 `ReportAgentTest` 的依赖、路由、DTO、JSON schema 和 Spring AI mock。
- **验证：** 先运行四个原失败测试，9 个测试全部通过；随后运行 `./mvnw.cmd test`，73 个测试通过、0 个失败、0 个错误。
- **沉淀：** 公开 DTO、路由和第三方 SDK 调用方式变更时，必须在同一变更中同步测试；全量测试绿色是后续功能开发的前提，而不是收尾动作。

### 2026-07-24：主页数据概览不展示，快捷问题点击无响应

- **现象：** 数据分析页能展示主题、趋势和告警，但主页概览显示默认 0；点击“玩法 Bug 为什么暴增”等快捷问题没有反应。
- **根因：** 聊天消息迁移至 Pinia Store 时，主页脚本误删了 `store`、`loading` 和 `isEmpty` 的声明。主页初始化读取 `store.workspaceId` 与快捷提问读取 `loading.value` 时触发未定义变量，导致概览请求和点击流程中断。
- **解决方案：** 恢复 Workspace Store 和两个响应式状态声明；新增前端回归测试，检查主页初始化与快捷问题依赖的状态声明存在。
- **部署问题：** Vite 构建产物写入 `src/main/resources/static`，而运行中的 Spring Boot 使用 `target/classes/static` 的旧资源。构建后执行 Maven 资源同步，确认运行服务加载新的前端入口。
- **验证：** `npm test` 通过，`npm run build` 成功；运行中的 Dashboard 接口返回非零数据、Top 主题和最近告警，服务入口已指向新前端资源。
- **沉淀：** 前端状态迁移必须覆盖“页面挂载”和“关键点击事件”两类回归点；前后端一体部署时，验证应包含静态资源同步链路，而不仅是源码构建。

### 2026-07-24：AgentRun 运行记录

- **现象：** 模型调用只有 `LlmMetrics` 日志，无法按工作区关联一次聊天调用的 Prompt/模型版本、输入摘要、输出、Token、耗时和失败原因；后续改 Prompt 或模型时没有可查询的事实基线。
- **根因：** 模型调用散落在业务服务与分析 Agent 中，原有日志没有领域主键、工作区隔离或可供 API 查询的持久化生命周期。
- **方案与取舍：** 新增通用 `AgentRunService` 和 `agent_run` 表，显式在 `ChatService` 的模型调用前后记录 `running`、`succeeded`、`failed`。没有采用全局 `ChatClient` 拦截或 AOP，因为它们无法可靠获得工作区、会话和业务证据。首版只接入聊天 Agent；异步分析 Agent 缺少统一工作区入口，强行一次接入会扩大投影链路改动范围。
- **安全边界：** `public_id` 作为对外 Trace，所有读写带 `workspace_id`；输入只保存经现有 PII 脱敏器处理并截断的摘要，不保存系统 Prompt、模型思维链、异常堆栈或工具调试参数。聊天尚未使用 RAG，检索版本明确记录为 `none`，证据 JSON 保持空值而不伪造证据。
- **实现：** 增加 V10 Flyway 迁移、`AgentRun` 实体、仓储、领域异常、运行服务与只读查询接口；聊天调用成功时提取 Spring AI Usage，失败时记录固定 `MODEL_CALL_FAILED` 码并继续由既有 API 错误边界处理。聊天响应同步返回 `trace_id`，可直接关联对应运行详情。
- **验证：** `AgentRunServiceTest` 覆盖脱敏摘要、成功状态和跨工作区拒绝；`ChatServiceTest` 覆盖模型成功/异常两条生命周期；`AgentRunControllerTest` 覆盖公开 Trace 响应；迁移契约测试覆盖 V10 表约束与索引。全量验证结果见本次开发记录后的测试命令。
- **沉淀：** 可评测不等于先建复杂评测平台。先把每一次真实调用变成隔离、可追溯的事实，再引入 Prompt 版本管理、金标集和评测运行器，才能判断策略变化的真实影响。

### 2026-07-24：会话持久化与短期记忆

- **现象：** 聊天消息仅保存在 Pinia 内存中，页面刷新后记录消失，模型也无法理解“刚才提到的玩法 Bug”等追问；原接口虽声明 SSE，实际只返回一次完整 JSON。
- **根因：** 后端不存在会话和消息领域模型，前端没有可恢复的会话标识；模型调用没有读取任何历史上下文，且曾向页面返回 `reasoning_content`。
- **方案与取舍：** 新增 `chat_session`、`chat_message` 和 V9 Flyway 迁移，以 `workspace_id` 作为当前唯一归属边界，并只对外暴露 UUIDv7。首版保存用户消息与模型最终回答，发送时读取最近 12 条消息；没有提前引入向量库、成员模型或自动滚动摘要。原因是项目尚未具备用户身份与真实长会话数据，过早引入这些组件会放大复杂度。原始思维链不保存、不展示。
- **实现：** `ConversationService` 集中做工作区解析、会话归属校验、创建/归档和消息读写；`ChatService` 读取受限历史后生成答案并持久化最终答案；`ChatController` 提供会话创建、列表、历史、归档及发送消息接口；Pinia Store 与首页支持新建、切换、刷新恢复和归档后新建。
- **验证：** `ConversationServiceTest` 覆盖工作区隔离与活动列表，`ChatServiceTest` 验证最近历史进入模型上下文且仅落库最终答案，`ChatControllerTest` 验证公开响应字段，`ProjectionSchemaMigrationTest` 覆盖 V9 结构契约；前端 `npm test` 与 `npm run build` 已运行通过。真实浏览器刷新 E2E 测试仍待补充。
- **沉淀：** 没有成员体系时只能承诺“工作区级记忆”，不能把它包装成个人记忆；会话层应先独立于 RAG 与评测体系落地，后续再通过 `AgentRun`、成员归属和摘要机制演进。

### 2026-07-24：聊天请求与模型服务兼容性调整

- **现象：** 在兼容模式接入模型服务时，聊天接口的流式调用和请求绑定存在兼容性风险。
- **根因：** 模型服务兼容模式与流式返回行为不完全一致；Java Record 的请求字段绑定需要明确 JSON 属性名以避免反序列化歧义。
- **解决方案：** 聊天服务使用非流式调用获取完整响应，并在 `ChatRequest` 的 `message` 字段上增加 JSON 属性标注。
- **验证：** 通过接口实现调整和后续代码提交完成兼容性修复；后续应补充基于模拟模型服务的聊天接口集成测试。
- **沉淀：** 外部模型服务的“兼容 API”不能只按接口名称判断，应在实际请求格式、响应格式、流式行为和错误场景上验证。

## 5. 当前待解决的核心问题

| 问题 | 影响 | 后续方向 |
|---|---|---|
| 超长会话尚无摘要 | 当前只注入最近 12 条消息，极长会话的早期上下文不会自动浓缩 | 观察真实会话长度后引入滚动摘要，并将其纳入评测。 |
| Agent 缺少统一评测 | 修改 Prompt、模型或检索策略后无法量化效果 | 建立金标问题集、AgentRun 记录和评测运行器。 |
| 回答可能过于笼统 | 用户难以判断结论是否可信或可执行 | 接入只读数据 Tool，强制输出证据、未知项和建议。 |
| 知识库尚未接入聊天 | 无法基于版本公告、SOP 等企业资料回答 | 在评测基础完成后，以 pgvector 实现带来源引用的混合检索。 |

详细优化顺序见：[Agent 优化 Todo](agent-optimization-todo.md)。

## 6. 维护约定

每完成一个关键能力、修复一个跨层问题或做出一项架构取舍，都在“关键问题与解决记录”中新增一节。每节必须包含：

```md
### 日期：问题标题

- **背景或现象：** 用户、接口、任务或系统出现了什么可观察问题。
- **根因：** 使用什么日志、测试、接口或代码证据确认了原因。
- **方案与取舍：** 为什么采用当前方案，以及明确放弃了什么更复杂或风险更高的方案。
- **实现：** 涉及的模块、API、数据模型或迁移。
- **验证：** 已运行的测试、构建、接口检查或人工验证结果。
- **沉淀：** 如何避免同类问题再次发生。
```

维护时遵循以下规则：

- 不编造业务效果、性能数字或模型准确率；没有统计时明确标为“未统计”。
- 不逐行复制代码；链接到实现、测试或设计文档即可。
- 将未完成的想法写入“当前待解决的核心问题”或优化 Todo，不伪装成已实现能力。
- 记录重点放在问题定位、技术取舍和验证闭环，这些内容可直接转化为 Java 后端或 Agent 开发岗位的面试案例。
### 2026-07-25：P2 证据化调查与可评测引用

- **背景或现象：** 聊天原先把固定近 7 天概览直接拼入 Prompt，模型无法按问题缩小查询范围，回答中的数字也没有可复核的来源。
- **根因：** 数据查询、调查决策和文本生成没有明确边界；评测只能检查事实覆盖，不能观察 Prompt 是否遵守引用格式。
- **方案与取舍：** 采用单 Agent + Java 规则规划器 + 只读 Tool 白名单。模型只消费服务端聚合或脱敏后的证据索引，不开放 SQL、仓储或自由函数调用；没有版本事件数据时明确返回未知，而不推断因果。
- **实现：** 新增 `agent/investigation` 调查模块；聊天调用记录计划和证据快照到 `AgentRun`，Prompt 升级为 `chat:v2`；聊天页面展示当次证据，金标评测增加证据引用率。
- **验证：** `mvnw.cmd clean test` 通过 114 项测试；前端 `npm test` 通过 4 项测试，`npm run build` 通过。
- **沉淀：** Agent 的“智能”应来自可审计的受控证据流，而不是让模型自由访问数据源；引用率只是格式代理，仍需结合事实覆盖和人工复核判断质量。

### 2026-07-26：真实数据闭环启动校验发现评测 Bean 未注册

- **背景或现象：** 为导入 2,092 条 TapTap 评论并运行知识库/RAG 评测，使用当前源码启动独立实例时，应用在 Spring 容器创建阶段失败；错误链指向 `GoldEvaluationRunner` 无法注入 `EvaluationCaseScorer`。
- **根因：** `EvaluationCaseScorer` 是被构造器注入的确定性评分组件，但类上没有 Spring 组件标记。现有单元测试直接 `new` 该类，未覆盖应用装配路径，因此编译和局部测试无法发现该问题。
- **方案与取舍：** 仅将评分器注册为 Spring `@Component`，不改造评测结构、不增加配置类或额外评分服务；评分逻辑保持确定性，不引入第二个模型。
- **实现：** 在 `EvaluationCaseScorer` 增加组件标记；新增 `EvaluationCaseScorerSpringRegistrationTest`，直接验证该组件标记，防止同类启动回归。
- **验证：** 新测试先在缺少组件标记时失败，再在修复后与 `GoldEvaluationRunnerTest` 一同通过。临时实例已越过评测 Bean 装配并完成 Flyway V1-V20 校验、Tomcat 启动；其后 API 返回 401，确认当前源码已启用 P4 JWT 安全边界，真实导入和 RAG 运行需使用有效登录令牌。
- **沉淀：** 依赖注入故障不能只依赖手工构造的单元测试；对被 `@Service` 构造器注入的协作类，至少保留一条能够发现组件注册缺失的回归测试。真实环境验证必须区分“应用未启动”和“认证边界拒绝”两类问题。

### 2026-07-26：TapTap 评论、知识库与 Agent 真实闭环验证

- **背景或现象：** 需要用真实评论验证企业知识库、RAG、CSV 导入、数据投影和 Agent 对话是否真正联通，而不是只依赖固定测试夹具。运行前还需要获得受 JWT 保护的业务接口访问能力。
- **根因：** 本地 PostgreSQL 没有既存测试账号；首次排查不能通过重置密码恢复访问。随后确认 bootstrap 已成功创建唯一的默认组织 Owner，后续 bootstrap 返回 401 是一次性初始化保护生效。临时验证脚本还暴露出 Windows PowerShell 对 UTF-8 JSON 和原生命令参数引号的处理差异，不能将脚本层 422/解析错误误判为业务 API 错误。
- **方案与取舍：** 以默认组织 Owner 的短期 JWT 调用现有 API；不在源码、配置、日志或文档中保存密码和令牌。知识库先上传为 `PENDING_REVIEW`，再逐份显式发布，避免未审核资料进入 RAG。没有为一次验证新增爬虫、数据模型或权限抽象。
- **实现：** 新增四份可维护的 Markdown 知识文档：当前 Workspace 的版本说明、已知问题、玩家反馈 SOP，以及组织通用的舆情分析手册。将两批 CSV 按现有 `feedback_text`、`occurred_at`、`source`、`external_ref` 映射导入，并保留 `rating`、`platform`、`source_url` 为扩展维度。
- **验证：** 四份文档均成功发布；2026-06-28 至 2026-07-11 批次导入 1,132 条，2026-07-12 至 2026-07-25 批次导入 960 条，重复和失败均为 0。异步投影完成后，看板聚合 2,092 条事件、5 个主题、1 条告警。一次真实聊天返回非空答案、8 条证据和 Trace ID `1f188b60-44d5-62c3-86f3-9f292f0fd92e`，验证知识与数据证据能共同进入 Agent 上下文。
- **发现的限制：** 当前 RAG 专项评测的 5 题会逐题调用 embedding 与聊天模型，整个 HTTP 请求同步执行；实际运行超过 8 分钟仍未返回，因此本次**没有**有效的 RAG 召回率、引用正确性或无依据回答率，不能伪造基线。日志缺少逐题开始/结束和 embedding 耗时，无法精确定位慢调用；另外现有评测没有“意图识别准确率”指标，不能把 P2 的规则路由能力写成已测准确率。
- **沉淀：** 真实数据闭环应同时记录成功结果与未完成的评测证据。下一步优先为评测增加逐题 Trace/耗时日志和应用级超时或异步任务化，再重新运行 RAG 基线；在有独立标注集前，不新增或宣传意图识别准确率。

### 2026-07-26：RAG 长评测任务化与超时收敛

- **现象：** RAG 评测在 HTTP 线程串行调用 5 道题的检索与模型回答，供应商阻塞时请求长期不返回，也无法定位到具体题目和阶段。
- **方案与取舍：** 复用 `async_task` 的持久化租约与恢复能力，新增 `rag_evaluation` 任务类型、独立 Worker 和前端轮询；不新增队列或数据库表。每题在独立受限线程池执行，55 秒后收敛为固定失败阶段并继续后续题；日志只记录题目 ID、阶段、耗时和可用 Usage，不记录问题、回答或知识正文。
- **真实验证：** 任务 `1f188cb0-336d-6700-be55-cb91fa48fe17` 完成了 queued→running→succeeded 的异步生命周期，但 5 题均在生成阶段触发 55 秒超时。后续重跑任务 `1f188cec-5429-6e40-885e-6b19805ac329` 产出一题成功、四题失败：召回率 25%、引用正确性 100%、无依据回答率 60%。这组数值混入了超时失败，不能作为有效质量基线。
- **方案补正：** 网络客户端增加 50 秒连接/读取上限，仍保留 55 秒单题 Future 兜底。Worker 的基线准入由“至少一题成功”收紧为“全部题目成功”；任一题失败即写入 `partial_failed`，不持久化新的 RAG 运行批次。任务 `1f188d08-ef9e-6223-9d33-97f203ac0f33` 的真实轮询结果为 `partial_failed` 且 `run_id=null`，证明该护栏已生效。
- **沉淀：** 异步化解决 Web 线程阻塞，不能替代供应商网络读超时和模型响应性能优化；在供应商稳定、全部题目成功前，RAG 基线必须明确标记为不可用而非输出伪指标。

### 2026-07-27：报告详情路由与受保护下载修复

- **现象：** 已完成报告在列表中点击“查看”进入白屏，点击“下载”则跳转到返回 `UNAUTHENTICATED` 的页面。
- **根因：** 前端注册了报告详情组件但遗漏 `/reports/:id` 路由；下载使用普通超链接导航，浏览器不会携带仅保存在 `sessionStorage` 的 Bearer Token。
- **方案与取舍：** 增加显式详情路由，并让报告与知识原文共用认证 `fetch` 的 Blob 下载工具；不放宽后端认证，也不把 Token 拼接到 URL 或持久化到本地存储。
- **实现：** 将路由定义独立为可验证的前端契约；报告列表与详情页下载改为显示进行状态的受保护下载调用，失败时保留页面并提示错误。
- **验证：** 下载、空闲操作状态与报告详情路由共 4 项 Node 回归测试通过；`npm.cmd --prefix frontend run build` 成功。完整前端 `npm test` 仍引用当前仓库缺失的既有测试文件，未将其误报为本次改动通过。
- **沉淀：** 只要认证凭证存在浏览器内存，所有受保护文件读取都必须走统一认证请求层；页面路由和 API 链接同样是可回归验证的前端契约。

### 2026-07-27：RAG 与金标评测任务的刷新恢复和失败可见性

- **现象：** RAG 评测提交后刷新页面会丢失内存中的 `task_id`，Gold 评测还会占用 HTTP 请求；模型调用出现超时或异常时，用户无法知道进行到哪一题、哪一阶段，或已完成结果是否仍被保留。
- **根因：** 已有 `AsyncTask` 仅用于 RAG 的后台执行，缺少“当前 Workspace 最近任务”的受控读取接口和逐题进度快照；Gold 评测仍是同步入口，前端没有统一的轮询恢复机制。
- **方案与取舍：** 复用单体内已有 `AsyncTask` 租约表和轮询模式，不引入消息队列、额外服务或新数据表。任务快照只保存题目 ID、计数、受控阶段、耗时和固定失败阶段；不保存题面、知识库原文、模型回答或思维链。
- **实现：** RAG 和 Gold 都在首题开始前持久化总题数，在每题终态后持久化累计耗时和失败阶段；评测页加载时按 Workspace 查询最近任务，若为 `queued/running` 自动恢复轮询，并同时展示终态摘要。RAG 仍只有全部用例成功时才持久化可比较的 run，任一失败为 `partial_failed` 且无有效 `run_id`。
- **验证：** 新增 `AsyncTaskTypeFactoryTest` 的进度快照回归用例；`mvnw.cmd test` 通过 75 项测试；`node --test test/evaluation-task-state.test.mjs` 通过 2 项测试；`npm.cmd --prefix frontend run build` 成功。前端构建仍保留既有 `outDir` 不清空和 CSS `@import` 顺序警告，未在本次外科式改动中处理。
- **沉淀：** 长耗时模型任务不能只返回“最终成功/失败”；至少应在持久化任务上保存可恢复的受控进度，并让终态保留最后一份快照，以便刷新、断连和失败场景都能解释任务结果。

### 2026-07-27：长评论多主题与主题级情绪复核

- **现象：** TapTap 长评常同时涉及多个方面，且会出现“画面好但网络卡顿”等正负混合表达；若整条评论只给一个主题或整体情绪，会吞掉运营问题并误导趋势解释。
- **根因：** 既有规则分类虽已限制每条反馈最多两个主题，但没有把情绪绑定到“反馈—主题”关联，也没有将超限、歧义、未分类和混合情绪暴露为人工可处理的候选。
- **方案与取舍：** 保留既有主题目录、历史链接和趋势口径，不重算历史投影，也不引入额外模型调用。新投影为每个主题关联写入受控情绪枚举，并把无法可靠收敛的结果写入独立候选队列；人工操作只改变候选状态，不能直接改写规则或历史数据。
- **实现：** 新增 Flyway V21/V22、`FeedbackReviewCandidate` 状态机、主题级情绪分析器和受保护复核 API；数据页展示脱敏样本并提供确认、忽略、提交新主题候选入口。所有候选读写均按 Workspace 隔离，API 仅暴露候选 UUID。
- **验证：** 主题级情绪与候选状态机测试均先失败后通过；`mvnw.cmd test` 通过 77 项测试；前端构建和后端编译成功。前端构建保留既有 Vite 输出目录与 CSS `@import` 顺序警告，未在本次范围处理。
- **沉淀：** 自动化分类的可信度不来自“永远给出答案”，而来自明确限制自动决策范围，并将低确定性结果保留为可审计、可人工确认、不会污染历史指标的候选。

### 2026-07-27: Explicit first Workspace creation

- **Observed:** A freshly bootstrapped Owner has organization membership but no Workspace, leaving Workspace-scoped controls disabled without a creation path.
- **Cause:** Bootstrap intentionally does not create a game or product data boundary, while the frontend store only loaded existing readable Workspaces.
- **Decision:** The sidebar shows a creation form only for the empty state and reuses the protected `POST /api/v1/workspaces` command. Successful responses select the returned `publicId`; failed requests retain the name and do not change the current Workspace. No API, schema, permission, or implicit default Workspace was added.
- **Verification:** Added Workspace creation and empty-state regression tests; ran `npm.cmd --prefix frontend test`, `npm.cmd --prefix frontend run build`, and `git diff --check`. The existing Vite outDir and CSS `@import` warnings remain out of scope.

### 2026-07-27: Knowledge source authentication and action feedback

- **Observed:** Opening a knowledge source through browser anchor navigation returned `UNAUTHENTICATED`; publish and delete actions had no per-version progress or failure feedback.
- **Cause:** Anchor navigation bypasses the frontend authenticated-fetch wrapper, so it cannot send the session-only Bearer token. Knowledge controller endpoints also lacked a shared Workspace access check before invoking document services.
- **Decision:** Source retrieval now uses authenticated fetch, creates a short-lived Blob download, and never exposes object-storage credentials. Publish, expire, and delete show progress and an error beside the affected version. The controller calls `WorkspaceAccessService.requireRead` for every knowledge endpoint before document access.
- **Verification:** Added frontend source/action regression assertions and controller authorization coverage; ran `mvnw.cmd test`, `npm.cmd --prefix frontend test`, `npm.cmd --prefix frontend run build`, and `git diff --check`.

### 2026-07-27：RAG 评测历史接口未返回指标导致页面空白

- **现象：** 5 题 RAG 评测全部 `succeeded` 且已写入历史，但页面只显示 `chat:v4 · knowledge:rrf:v1` 和时间，三项质量指标不出现。
- **根因：** `GET /evaluations/rag` 的 `RagRunSummaryResponse` 刻意省略 `metrics_json`；前端又用始终为 `null` 的 `ragResult` 控制指标卡片渲染，成功后还执行 `ragResult.value = null`。
- **方案与取舍：** 在历史列表响应中附带已持久化的脱敏 `RagEvaluationMetrics`，前端改从最新/选中批次读取指标；不新增详情接口，也不把逐题结果或模型回答暴露给浏览器。
- **实现：** 扩展 `EvaluationController.RagRunSummaryResponse`；`Evaluations.vue` 用 `latestRagRun` 展示指标并支持点击历史批次切换。
- **验证：** `EvaluationControllerTest` 5/5 通过；前端回归 2/2 通过；`npm run build` 与 `process-resources` 成功。数据库已有指标：`retrievalRecallRate=1.0`、`citationCorrectnessRate=1.0`、`ungroundedAnswerRate=0.2`。
- **沉淀：** 异步任务成功后页面必须读取持久化历史中的指标字段，不能依赖未赋值的临时前端状态。

### 2026-07-27：RAG 评测生成阶段 50 秒超时根因与默认值调整

- **现象：** 上传并发布知识库后触发 RAG 评测，5 道题均在 `generation_failed` 结束；`generation_latency_ms` 稳定在约 50000ms，检索仅 267–316ms。
- **根因：** DashScope `chat/completions` 在 RAG 评测场景下响应超过既有 50 秒 HTTP 读超时；`HttpTimeoutException: Request cancelled` 与 `insightflow.agent.http-read-timeout-seconds=50` 一致。知识库检索正常，不是切片或 pgvector 故障。
- **方案与取舍：** 成对提高 HTTP 读超时（110 秒）与单题应用层超时（120 秒），并将整批任务租约增至 720 秒以覆盖 5 题串行最坏耗时；不缩短题集、不放宽“全部成功才入基线”规则，也不伪造 Token 指标。
- **实现：** 调整 `application.yml` 默认值及 `AgentConfiguration`、`RagEvaluationCaseExecutor`、`RagEvaluationTaskService` 的 `@Value` 兜底；在 `application-local.yml.example` 提示本地勿保留旧的环境变量覆盖。
- **验证：** 待运行相关单元测试；用户需重启后端并确认本地未设置 `AGENT_HTTP_READ_TIMEOUT_SECONDS=50` / `RAG_EVALUATION_CASE_TIMEOUT_SECONDS=55` 后再重跑 RAG 评测。
- **沉淀：** RAG 评测失败若检索耗时正常而生成耗时贴近 HTTP 读超时，应优先核对超时配置与 DashScope 响应时延，而不是怀疑知识库上传链路。

### 2026-07-27: Knowledge version actions must not lock unrelated versions

- **Observed:** A request that did not settle left the global source or lifecycle pending flag populated, causing every visible source, publish, and delete button to render disabled.
- **Cause:** The page represented in-flight state with one global key, even though each request targets one specific document version and source or model calls may take longer than a normal UI interaction.
- **Decision:** Pending state is now keyed by version so only the affected row is mutually exclusive. Fetch requests use a 60-second client timeout; timeout clears that row and shows a refresh-and-confirm message, without assuming the server-side command was cancelled.
- **Verification:** Added a frontend regression test covering scoped pending state and timeout handling; `node --test frontend/test/knowledge-runtime-state.test.mjs`, `npm.cmd --prefix frontend run build`, `mvnw.cmd process-resources`, and `git diff --check` passed.

### 2026-07-28：知识库多版本并存与发布可选下线旧版

- **背景或现象：** 每次上传都会新建一篇文档；发布新版本时同文档旧 `PUBLISHED` 会被强制 `EXPIRED`，无法保留 1.3 与 1.4 公告等多版本同时可被 RAG 检索。
- **根因：** V13 部分唯一索引 `uk_knowledge_document_published_version` 限制每文档仅一个已发布版本；`KnowledgePublishingService.publish` 无条件 expire 旧版；缺少向已有文档追加上传的 API 与 UI。
- **方案与取舍：** Flyway V24 删除该部分唯一索引，允许多个 `PUBLISHED` 并存；新增 `POST .../documents/{documentId}/versions` 追加上传待审核版本；发布 API 增加可选 `expire_previous_published`（默认 false，用户勾选才下线旧版）。检索 SQL 仍只过滤 `status = 'PUBLISHED'`，无需改向量层；RAG 评测 `asPublishedDocument` 改为取 `published_at` 最新的一版。
- **实现：** `KnowledgeDocumentService.uploadVersion`、`KnowledgePublishingService.publish(..., expirePreviousPublished)`、`KnowledgeDocumentController.PublishRequest`；`Knowledge.vue` 增加「上传新版本」与发布确认框（含「同时下线旧版」勾选）。
- **验证：** `KnowledgePublishingServiceTest`（默认保留旧版 / 显式下线）、`KnowledgeDocumentServiceTest.uploadVersionAppendsPendingVersionToExistingDocument`、`KnowledgeDocumentControllerTest`、`KnowledgeRagSchemaMigrationTest`（V24）、`RagEvaluationFixtureFactoryTest`、`frontend/test/knowledge-runtime-state.test.mjs` 通过；`npm run build` 成功。
- **沉淀：** 「多版本并存」与「替换式发布」由发布时的显式用户选择表达，不应再靠数据库唯一索引强制替换；同文档多版本命中 RAG 时依赖引用 `version_no` 与后续 Phase R2 按文档限流缓解混用风险。

### 2026-07-28：运营调查型 RAG 人工金标数据集领域模型（G1）

- **背景或现象：** 现有 RAG 评测由 `RagEvaluationFixtureFactory` 按当前已发布文档动态生成题目，只能验证文档前缀命中，无法承载人工标注的关键事实、禁止断言、拒答预期与冻结门禁集。
- **根因：** 缺少版本化、不可变的人工金标存储；题目与证据未与 document/version/chunk 公开 ID 绑定；发布/冻结后无法保证 Runner 加载快照可复核。
- **方案与取舍：** 新增四表 `rag_gold_dataset` / `rag_gold_case` / `rag_gold_case_evidence` / `rag_gold_case_assertion`（Flyway V27）；数据集按 Workspace+Organization 隔离，状态机 DRAFT→PUBLISHED→FROZEN，发布时计算 SHA-256 checksum；只读 `RagGoldDatasetReadService` 供后台 Runner 加载，写入 `RagGoldDatasetCommandService` 仅供导入脚本使用，不暴露用户 API 或前端。
- **实现：** 枚举 split/question_type/difficulty/evidence_granularity/assertion_type；快照 DTO `RagGoldDatasetSnapshot` / `RagGoldCaseSnapshot`；证据只存 document/version/chunk 公开 UUID。
- **验证：** `RagGoldDatasetSchemaMigrationTest`、`RagGoldDatasetTest`、`RagGoldCaseEvidenceTest`、`RagGoldDatasetChecksumTest`、`RagGoldDatasetCommandServiceTest`、`RagGoldDatasetReadServiceTest` 共 20 项通过。
- **沉淀：** 动态 Fixture 应降级为链路回归；生产质量门禁必须引用已发布/冻结的人工金标版本，Runner 持久化 dataset key/version/checksum 与 case_key 列表。

### 2026-07-28：Phase C — Pack 级 LLM Topic Skill

- **背景或现象：** L1 规则优先后约 41% 仍落入 `topic_general`；需在不动 L2 主路径、不替换规则分类器的前提下，对 general 子集做受控 LLM 补标以降低笼统桶占比。
- **根因：** Pack `topic-rules.toml` 无法覆盖全部口语表达；Phase A/B 将零命中统一写 `topic_general` 而非进复核，缺少第二层补位能力。
- **方案与取舍：** 新增 `PackTopicClassifier` 编排「规则 → LLM」：仅规则零命中时且全局+Pack 双开关开启才调用 `TopicPackTopicLlmSkill`；LLM 只能从当前 Pack catalog 选键（含 `topic_general` 兜底）；置信度低于阈值仍写 general，但把 `topic_llm_prompt_version` / `topic_llm_confidence` 写入 `feedback_projection_annotation`（Flyway V26）。门控优先 `expr_complaint`/`expr_suggestion` 且文本长度 ≥ 15，跳过纯好评。
- **实现：** `ChatTopicPackTopicLlmSkill` + `OperationalPromptCatalog.pack-topic:v1`；`AnalysisConfiguration` 按 ChatClient 有无装配 NoOp/Chat 实现；`WorkspaceProjectionExecutionService` 集成编排与标注追溯；`pack.toml` 增加 `topic_llm_skill_enabled`。
- **验证：** `mvnw.cmd test -Dtest=TopicLlmGateTest,PackTopicClassifierTest,ChatTopicPackTopicLlmSkillTest,ProjectionAnnotationWriterTest,WorkspaceProjectionExecutionServiceTest,TopicPackLoaderTest,ProjectionSchemaMigrationTest,ChaoziranTopicRulesClassifierTest` 34 项通过。
- **沉淀：** LLM 补标是 Pack 可选 Skill，不是 L1 主路径；启用需 `TOPIC_LLM_SKILL_ENABLED=true` + `topic_llm_skill_enabled=true` + `AGENT_ENABLED=true` + `DASHSCOPE_API_KEY`；general 占比变化须重投影后观测，Prompt 升版通过标注行 `topic_llm_prompt_version` 追溯。

### 2026-07-28：Wave 2 A2 Phase B — 平台 L2 表达分类与 Dashboard API

- **背景或现象：** L1 主题分类与复核队列已降噪（Phase A），但 Dashboard 仍只有 L1 视角，无法按「建议 / 抱怨 / 表扬 / 中性 / 其他」做跨游戏可比的首屏粗分，也无法从 L2 钻取到 L1 议题分布。
- **根因：** 投影管线此前只写 `feedback_issue_link` 与复核候选，缺少 L2 标注快照与日聚合表；Dashboard 服务也未暴露表达维度汇总。
- **方案与取舍：** 新增平台级 `expression-rules.toml`（五类 L2，零命中兜底 `expr_other`）与 `ExpressionClassifier`，在投影事务内与 L1 链接并行写入 `feedback_projection_annotation` 和 `expression_metric_bucket`（Flyway V23）。Topic Pack（`game-chaoziran:v1`）仅加载 catalog/rules 供 Dashboard 展示 Pack 元信息，**未**切换 L1 生产分类源——旧 `issue-rules.toml` 的 8 类 key 与新 `topic_*` key 需产品确认 alias 策略后再迁移，避免污染历史 `feedback_issue_link`。告警副屏（`alert_eligible` 子集）按 wireframe 留待后续。
- **实现：** `WorkspaceProjectionExecutionService` 集成 `ProjectionAnnotationWriter` 与 `ExpressionMetricBucketService`；`DashboardService` 新增 `expressionSummary`、L2→L1 钻取与交叉样本 API（`GET .../expressions/{key}/topics`、`.../samples`），均按 `workspace_id` 隔离、对外只暴露 `public_id`。
- **验证：** 子任务 [Wave2 A2 Phase B backend](5ed0dbf5-497b-4ade-89a6-89fa6d358d80) 报告全量 `mvnw.cmd test` 193 项通过；协调者复跑 A2 相关子集（`ExpressionClassifierTest`、`ProjectionAnnotationWriterTest`、`ExpressionMetricBucketServiceTest`、`DashboardServiceTest`、`DashboardControllerTest`、`ProjectionSchemaMigrationTest`）通过。**前端 `Dashboard.vue` 尚未消费 `expressionSummary`**，L2 看板 UI 仍为待办；`L2 非 other ≥ 85%` / `复核 < 100` 两项数字验收需对真实 Workspace **重新执行投影**后手动确认，历史已投影数据不会自动回填 L2 标注。
- **沉淀：** L2 与 L1 分层后，Pack 规则接管 L1 生产分类是独立的产品/兼容决策，不能与 L2 基础设施混在同一发布里静默切换；Dashboard 新字段已就绪，前端接线与重投影验收应作为 Phase B 收尾步骤。

### 2026-07-28：Wave 2 A2 Phase B 收尾 — Dashboard L2 UI + Workspace Topic Pack 切换

- **背景或现象：** Phase B 后端已提供 `expressionSummary` 与 L2→L1 钻取 API，但前端 `Dashboard.vue` 未消费；L1 投影仍读全局 `issue-rules.toml`，Workspace 无法绑定 Pack，与 spec「粗→细 + 按游戏 Skill Pack 钻取」不一致。
- **根因：** MVP 阶段 `TopicPackLoader` 仅作展示用 Bean；`Workspace` 无 `topic_pack_id`；投影编排注入全局 `RuleFirstIssueClassifier` 单例。
- **方案与取舍：** Flyway V25 增加可空 `workspace.topic_pack_id`（null 回退 `insightflow.analysis.topic-pack-directory`）；新增 `TopicPackRegistry` 扫描 classpath packs；投影时按 Workspace 解析 Pack 并 **仅对新投影** 使用 `topic-rules.toml`——历史 `feedback_issue_link` 不做 issue key→topic_* alias 映射，避免 silent 改写；Pack 切换 API 需 OPERATOR+，切换后不自动重投影。
- **实现：** `WorkspaceTopicPackService` + `TopicPackController`（`GET /api/v1/topic-packs`、`GET/PUT .../workspaces/{id}/topic-pack`）；`WorkspaceProjectionExecutionService` 运行时构造 Pack 驱动分类器；`Dashboard.vue` 首屏 L2 五类占比条 + 7 天折线 + 点击钻取 L1/样本 + Pack 下拉切换；路由 `/dashboard` 与侧栏入口。
- **验证：** `mvnw.cmd test -Dtest=TopicPackRegistryTest,WorkspaceTopicPackServiceTest,TopicPackControllerTest,WorkspaceProjectionExecutionServiceTest,DashboardServiceTest,DashboardControllerTest,ProjectionSchemaMigrationTest,RuleFirstIssueClassifierTest` 通过；`frontend/npm test`（含 `dashboard-runtime-state.test.mjs`）25 项通过；`frontend/npm run build` 成功。
- **沉淀：** Pack 切换与 L1 规则生效是「配置变更 + 手动重投影」两步操作；旧投影 link 与新 topic_* key 可并存，看板钻取按实际 catalog 统计，不应假设全量历史已迁移。

### 2026-07-28：Dashboard alert_eligible 告警副屏（Phase B P1 收尾）

- **背景或现象：** Phase B 首屏 L2 主视图与 L2→L1 钻取已交付，但 wireframe §7.2 要求的「可行动议题告警（alert_eligible 子集）」副屏仍为 todo，无法单独查看 Pack 内 eligible 议题的窗口内反馈量与趋势。
- **根因：** `alert_eligible` 仅在 Topic Pack `topic-catalog.toml` 与 `TopicPackTopic` 中定义，Dashboard 服务未将其聚合为独立 API，前端也无只读副屏入口。
- **方案与取舍：** 新增 `GET .../dashboard/alert-eligible`，从 Pack 目录读取 eligible 键，与 `feedback_issue_link`（窗口内计数）和 `issue_metric_bucket`（日趋势）交叉聚合；最近告警从现有 `alert` 表过滤 eligible issue_id。副屏折叠展示于 L2 主视图下方，**只读**、不触发告警状态变更；`topic_general` 固定 excluded。
- **实现：** `DashboardService.getAlertEligibleOverview` + `AlertEligibleOverviewResponse`；`DashboardController` 路由；`Dashboard.vue` 可折叠副屏（eligible 议题卡片 + 趋势折线 + eligible 最近告警）；`DashboardServiceTest`/`DashboardControllerTest` 与 `dashboard-runtime-state.test.mjs` 增补断言。
- **验证：** `mvnw.cmd test -Dtest=DashboardServiceTest,DashboardControllerTest` 通过；`frontend/npm test` 34 项通过；`frontend/npm run build` 成功。
- **沉淀：** `alert_eligible` 是 Pack 级产品标记（非 DB 列），决定哪些 L1 议题参与 EWMA 告警筛选；副屏与首屏 L2 解耦，Phase E 可在不改 UI 结构的前提下收紧告警引擎仅扫描 eligible 子集。

### 2026-07-28：RAG 金标题集从“每类型一题”扩展为多题 + 跨文档混淆

- **背景或现象：** RAG 专项评测题集由 `RagEvaluationFixtureFactory` 按当前 Workspace 可见的已发布文档动态生成，此前每种文档类型固定只挑一篇、只问一题，题集上限为“文档类型数 + 1 道无依据题”（≤5 题）。语料仍处于个位数文档时，召回率和引用正确性容易被“四篇文档对四道模板题”拉高，无法验证同类型多文档场景下是否会引错文档。
- **根因：** 题目生成逻辑把“文档类型”和“证据期望”做了一对一绑定（`toMap` 按类型去重只保留最新一篇），题目文本也是类型级别的固定字符串，天然无法表达“同类型第二篇文档”的存在，也没有章节级的多角度提问。
- **方案与取舍：** 改为按 `(文档类型, 文档新旧序号)` 定位的固定问题模板集合：`documentIndex=0` 指向该类型最新发布文档，`1` 指向次新。模板文本仍完全固定、不拼接用户可编辑的文档标题（避免提示注入面）；同一文档可挂多条模板以检验章节级检索（呼应 R1 标题切分 + embed 前缀），第二篇同类型文档出现时自动补出跨文档混淆题。放弃了“引入独立的静态 JSON 金标文件”方案——现有动态生成机制已能满足 15 题目标，额外引入一套平行的静态题集会造成两套证据口径需要同步维护，违反 KISS。
- **实现：** 重写 `RagEvaluationFixtureFactory`（按 `KnowledgeDocumentType.values()` 遍历、每类型固定模板列表、按实际匹配到的文档数量决定是否追加序号后缀，避免语料不足时虚增或伪造题目）；`docs/knowledge-sources/` 由 4 篇扩充为 9 篇（每种既有类型新增一篇形成跨文档混淆语料：次新版本公告、历史归档已知问题、玩家常见问题 FAQ、玩法机制与舆情判读参考；另加一篇版本公告模板作为纯结构参考，不参与出题）。语料语境改为基于《超自然行动组》真实公开信息（核心搜索/战斗/撤离循环、怪物与地图名称、举报治理机制、社区高频反馈主题）改写为内部知识库风格，未逐句复制原文。
- **验证：** `mvnw.cmd -Dtest=RagEvaluationFixtureFactoryTest,RagEvaluationCaseExecutorTest,RagEvaluationTaskRunnerTest,RagEvaluationTaskQueryServiceTest,RagLiveEvaluationRunnerTest,RagEvaluationTaskCommandServiceTest,EvaluationCaseScorerSpringRegistrationTest,RagGoldEvaluationRunnerTest,GoldEvaluationRunnerTest,EvaluationCaseScorerTest,GoldEvaluationDatasetLoaderTest,EvaluationRegressionGateTest test` 全部通过（含新增的多文档、跨文档混淆用例）。全部 9 篇文档补齐发布后，动态题集预期为 14 道类型题 + 1 道无依据题 = 15 题；该数字未在真实数据库环境跑通验证，需要用户在 UI 中把 9 篇文档逐一发布后重跑一次 RAG 评测确认。
- **沉淀：** 动态生成的金标题集比静态题库更贴合“语料变化后题目自动跟着扩缩”的目标，但代价是题目正确性依赖模板与语料同步维护；新增文档类型的第二篇资料时，必须同步检查是否需要为该类型追加跨文档模板，否则新文档只会静默地不被任何题目覆盖。

### 2026-07-28：Dashboard / 数据分析统一分析日期范围

- **背景或现象：** L2 分布曾临时改为「全量标注 + 趋势近 7 天」，与 L2→L1 钻取口径不一致；批量导入历史 CSV（如 6/27–7/11）时 wall-clock「今天往前 7 天」会把有效桶全部滤掉；Dashboard 与数据分析页无共享日期控件。
- **根因：** 各聚合路径各自解析时间窗口，且默认锚定在 wall-clock 而非数据覆盖 `windowEnd`；前端无 `from`/`to` 传参，样本 DTO 缺少 `occurred_at` / 来源。
- **方案与取舍：** 新增 `AnalysisWindowResolver`——未传参时默认 `[windowEnd-7d, windowEnd]`，显式 `from`/`to` 优先并 clamp 到 coverage；「全部」由前端传 coverage 起止日。L2 分布/趋势/钻取、L1 主题数/趋势/样本统一按 `feedback_event.occurred_at` 过滤；告警与复核队列**不**跟分析范围。不做 mode 枚举、不持久化用户偏好（MVP）。
- **实现：** `GET /dashboard`、`/issues`、`/issues/{key}`、`/expressions/...` 增加可选 `?from=&to=`，响应带 `analysisWindow`；`FeedbackSample`（text/occurredAt/sourceKind）；复核 `CandidateResponse` 补 `feedbackOccurredAt`/`sourceKind`；前端共享 `AnalysisDateRange.vue` + `analysis-window.js`，接入 `Dashboard.vue` 与 `Data.vue`。
- **验证：** `AnalysisWindowResolverTest`、`DashboardServiceTest`、`DashboardControllerTest` 通过；`frontend/test/analysis-window.test.mjs`、`dashboard-runtime-state.test.mjs` 与 `npm run build` 通过。
- **沉淀：** 分析口径以 `occurred_at` 为唯一时间轴；默认窗口必须锚定数据截止日而非系统日期，否则历史导入场景必然出现「有数据但图表为 0」。

### 2026-07-28：Agent Tool / 报告支持 L2 与 L2×L1 查询

- **背景或现象：** Dashboard 已有 L2 分布与 L2→L1 钻取 API，但 Agent 调查层（P2）与异步报告仍只聚合 L1 主题，无法回答「吐槽占比」「建议里主要议题」等表达层问题。
- **根因：** `InvestigationToolType` 与 `InvestigationPlanner` 未注册 L2 相关 Tool；`AnalysisReportTaskRunner.buildMergedData()` 只填充 `issueMentions`；报告 Prompt 未携带 L2 字段。
- **方案与取舍：** 在 `InvestigationToolService` 注入 `DashboardService` 复用同一分析窗口与标注聚合（与 Dashboard API 口径一致），新增三个只读 Tool；新增 `EXPRESSION_INQUIRY` 意图与中文关键词规则；报告侧扩展 `MergedData.expressionMentions` 并将 Prompt 版本升至 `report:v2`。不在 Agent 层重复实现 SQL/JOIN，避免与 Dashboard 漂移。
- **实现：** `EXPRESSION_DISTRIBUTION` / `EXPRESSION_TOPIC_DRILLDOWN` / `EXPRESSION_TOPIC_SAMPLES`；`resolveExpressionKey()` 匹配 expr_* 键、中文展示名与规则正向词；`REPORT_GENERATION` 计划追加 L2 分布 Tool；`OperationalPromptCatalog.renderReportUserPrompt` 增加 L2 段落。
- **验证：** `InvestigationPlannerTest`、`InvestigationToolServiceTest`（含 L2 分布/钻取/样本）、`ReportAgentTest` 通过。
- **沉淀：** L2 Agent 证据与 Dashboard 应同源（`DashboardService`）；L1 Tool 仍用固定 UTC 14/7 天窗口，L2 Tool 用 `AnalysisWindowResolver`——产品层需知两种窗口策略并存，后续可统一。

### 2026-07-28：运营 RAG 金标 ops-rag-v1（400 题 + 导入契约）

- **背景或现象：** 动态 `RagEvaluationFixtureFactory` 题集无法作为正式质量门禁；需要版本化人工金标、三 split（dev/val/frozen）及 evidence 绑定已发布语料 chunk。
- **根因：** 生产评测缺少不可变数据集、checksum 与 manifest 解析；seed 若直接写 UUID 会在语料 re-publish 后静默失效。
- **方案与取舍：** seed 存 `document_ref + version_no + chunk_no`，导入时经 `corpus-manifest.json` fail-fast 解析为公开 UUID；三份独立 JSON（240/80/80）各自 publish，frozen-80 额外 freeze；不提供 HTTP/前端入口，仅 `rag-gold-import` Profile + PS 脚本。
- **实现：** `evaluation/rag/gold/seeds/schema.json` + `ops-rag-v1-*.json`（400 题，含 4 组版本冲突）；`RagGoldCorpusManifestResolver` / `RagGoldSeedValidator` / `RagGoldSeedImporter` / `RagGoldImportRunner`；`scripts/generate-rag-gold-seeds.py`、`scripts/import-rag-gold-dataset.ps1`。
- **验证：** `mvnw.cmd test -Dtest=com.insightflow.evaluation.rag.gold.**` 通过；本机已 import 三 split（dev-240 / val-80 / frozen-80 共 400 题，public_id 见 import 日志）；导入脚本需 `local,rag-gold-import` 双 profile。
- **沉淀：** 语料变动后先 `python scripts/export-corpus-manifest.py` 再 regen/import；Runner（Task D）应加载 `ops-rag-v1` 指定 version 而非动态 Fixture。

### 2026-07-30：语料 v2 重发布 + R1 候选召回门槛验证

- **背景或现象：** R1 `knowledge:rrf:v2` 上线后 L3 retrieval-only 候选 doc R@30 仅 76.7%、chunk R@50 61.7%，未达 70% 门槛；漏斗显示 `candidateSourceLexicalOnly=0`，且旧金标 evidence 仍指向 `version_no=1` chunk，与重发布后语料不一致。
- **根因：** （1）V29 前切片无 `lexical_text`，词法 CTE 对 enriched 字段无增益；（2）PostgreSQL `simple` 配置对中文 `websearch_to_tsquery` 几乎不产生 lexical 命中，RRF 实际为纯向量；（3）seed/manifest 与 DB 语料版本漂移导致评测证据 UUID 失效。
- **方案与取舍：** 新增 `KnowledgeCorpusRepublishService` + `republish-knowledge-corpus.ps1` 批量 expire→delete→upload→publish（保留 `document_id`，31 篇升至 v2）；三份 seed 的 `version_no` 全量改 2 并重导入金标；用 R0 retrieval-only 漏斗在 dev-fast-40 / dev-240 对比。暂不启动 R2 精排直至 Candidate Recall@50 达标；词法 hybrid 失效单独列为 R1.5 待办（中文分词插件或 keywords 列）。
- **实现：** 31 文档 / 441 chunk 重发布；`corpus-manifest.json` 与 seed 对齐 v2；删除旧 400 cases 后重导入 dev-240/val-80/frozen-80；评测 run `1f18bc46`（L2）、`1f18bc4b`（L3）。
- **验证：** L3 run `1f18bc4b`：240/240 成功；Candidate doc R@30 **97.1%**、chunk R@30 **66.3%**、chunk R@50 **71.7%**；final Top8 chunk R@8 **39.2%**（较 v1 金标 run `1f18bc28` 的 31.7% 提升）；P95 **452ms**；CROSS 双文档命中率 97.9%。`candidateSourceLexicalOnly` 仍为 0。
- **沉淀：** 语料/金标必须同版本联调，checksum 变更后不可 carry-forward 旧批次；R1 候选上限已证明，下一步是 R2 精排或 R1.5 中文词法；漏斗「来源计数」是 hybrid 是否生效的硬指标。

### 2026-07-30：R2 精排骨架（Cross-encoder + RRF 回退）

- **背景或现象：** R1 候选 Recall@50 在 dev-240 达 71.7%，但 final Top8 chunk R@8 仅 39.2%，需专用精排拉升最终排序而不掩盖候选缺失。
- **根因：** 生产路径 RRF Top50 直接截断 Top8，缺少 query-passage 交叉编码；Chat 模型逐条打分成本高且不可复现。
- **方案与取舍：** 新增 `KnowledgeReranker` 端口与 `CrossEncoderKnowledgeReranker`（DashScope `qwen3-rerank`，RRF 前 30 条）；失败/超时回退 `RrfOnlyKnowledgeReranker`；默认 `reranker.enabled=false`，评测用 `--reranker=on` 做离线对比；Prompt 仍 `chat:v4`。
- **实现：** `KnowledgeRerankDocumentText` 格式化 title/type/version/effective/section/content；`JdbcKnowledgeVectorStore` 返回精排元数据；`KnowledgeSearchTool` 精排后出 Top8；CLI/PS `-Reranker`；单测 4 项通过。
- **验证：** `mvnw.cmd test -Dtest=KnowledgeSearchToolTest,KnowledgeRerankDocumentTextTest,CrossEncoderKnowledgeRerankerTest,KnowledgeRerankerSelectorTest,...` 通过；**尚未**跑 dev-fast-40 `--reranker=on` 对比与生产门槛。
- **沉淀：** 精排版本写入 `knowledge:rrf:v2+rerank:qwen3-rerank`；候选漏斗仍用 RRF Top50，final 指标才反映精排贡献。

### 2026-07-30：RAG 金标评测 Phase A/B（可信度 + evidence requirement groups）

- **背景或现象：** R2 精排 A/B 显示 chunk R@8 净提升为 0，且 18/40 题「文档 Top8 命中、chunk Top8 未中」；旧评测用 ThreadLocal 注入精排、多 evidence 题用 any-hit 计分、语料「评测锚点」节污染检索，导致离线对比不可信、多证据题指标过宽。
- **根因：** （1）`KnowledgeRerankerSelector` ThreadLocal 使 E2E 与 retrieval-only 精排开关不一致；（2）缺少逐题 RRF/候选/精排 rank 诊断，无法定位 Top30 截断与降权；（3）金标 evidence 无 OR/AND 语义，dev-002 等题任一 chunk 命中即算满分；（4）17 篇语料 md 含人工锚点文本，仍可能被切片进 DB。
- **方案与取舍：** Phase A 移除 ThreadLocal，统一经 `KnowledgeRetrievalOptions.withReranker` 传播；新增 `retrievalDiagnostics` 与 `finalEvidenceCoverageAt8` 等扩展指标；Phase B 引入可选 `requirement_key`（组内 OR、组间 AND），Flyway V30；dev-fast-40 多证据题已标注 key；源 md 删除锚点节。**语料 re-publish 与金标 re-import 留待环境执行**，旧 run 不可与新区间直接对比。
- **实现：** `RagGoldRetrievalDiagnosticsComputer`、`RagGoldManualExtendedMetrics`；`RagGoldEvidenceMatcher` requirement 组；`schema.json` + V30 + seed/checksum；`ChatService` 改用 `resolveRetrievalVersionLabel(null)`；17 篇 `docs/knowledge-sources/*.md` 删锚点节。
- **验证：** `mvnw.cmd test -Dtest=KnowledgeRerankerSelectorTest,RagGoldEvidenceMatcherRequirementGroupTest,RagGoldRetrievalDiagnosticsComputerTest,RagGoldManualEvaluationScorerTest,RagEvaluationCaseExecutorTest,...` 共 **44 项**通过。**环境验证（2026-07-30）：** 语料 re-publish 31/31→v3（424 chunk）；金标三 split 重导入（dev-240 checksum `bf1968f0…`）；dev-fast-40 retrieval-only run `1f18bde3`：40/40 成功；`chunkRecallAt8`/`chunkRecallAt8AnyEvidence` **42.5%**、`finalEvidenceCoverageAt8` **25%**（requirement 组更严）、Candidate chunk R@50 **67.5%**、P95 **490ms**；`retrievalDiagnostics` 与 `chunkRecallMetricMode=any_evidence` 已写入 JSON。
- **沉淀：** 多 evidence 题应显式建模 requirement 组；`finalEvidenceCoverageAt8` 显著低于 any-hit chunk R@8（25% vs 42.5%），说明多证据题 partial hit 被正确暴露；re-publish 删锚点后末尾 chunk 减 1，需 manifest 对齐脚本；checksum 变更后旧 run 不可对比。

### 2026-07-30：RAG 金标 Phase C（回归门禁 + R2 精排 A/B）

- **背景或现象：** Phase A/B 交付后需将 inalEvidenceCoverageAt8 纳入 frozen/基线对比门禁，并在语料 v3 + requirement_key 金标上重跑 RRF vs qwen3-rerank 全量对比，判断 R2 是否达生产门槛。
- **根因：** 旧门禁仅看 chunkRecallAt8（any_evidence），多证据题 partial hit 退化不可见；此前 R2 A/B 在 v2 语料上 chunk 净提升为 0，不可与新口径对比。
- **方案与取舍：** 门禁新增 inal_evidence_coverage_at8_regressed（±2pp 与既有质量项一致）；dev-fast-40 与 dev-240 全量各跑 RRF / rerank 两批 retrieval-only；**不启用生产精排**——dev-fast-40 上 CROSS dual-hit@8 精排后从 70% 降至 60%，val/frozen 精排未测。
- **实现：** RagGoldManualEvaluationRegressionGate 比较 inalEvidenceCoverageAt8；RagGoldManualEvaluationRegressionGateTest 新增 partial-only 退化用例。
- **验证：** mvnw.cmd test -Dtest=RagGoldManualEvaluationRegressionGateTest,RagGoldManualEvaluationCliRunnerTest 通过。dev-fast-40：RRF 1f18bde3 chunk R@8 42.5% / finalEvidence 25% → rerank 1f18be30 chunk **47.5%**（+5pp）/ finalEvidence 25% / rerank P50 254ms。dev-240：RRF 1f18be34 chunk 41.3% / finalEvidence 31.7% / Candidate R@50 72.9% → rerank 1f18be38 chunk **47.1%** / finalEvidence **38.3%** / P95 424ms；
erankFallbackRate=0。
- **沉淀：** 精排在 dev-240 全量上对 chunk R@8 与 finalEvidenceCoverage 均有 ~5–7pp 增益，但 CROSS dual-hit@8 在 dev-fast-40 上退化；生产启用仍需 val/frozen 不退化验证；门禁应同时监控 chunk 与 requirement 全覆盖两条线。

### 2026-07-30：Rerank 集合选择实验与 val 门禁否决

- **背景或现象：** Phase C 的 qwen3-rerank 能提升总体 chunk/requirement 覆盖，但会挤出 CROSS 多文档证据；需要验证 Top50、RRF 稳定性融合和软多样性是否能在保留总体收益时恢复双文档命中。
- **根因：** 原实现纯按 pointwise rerank 分数截断 Top8，不保护 RRF 强锚点，也不考虑同文档重复；同时旧聚合缺少按题型 requirement coverage 与 gained/lost/demotion，fallback 还可能被误归因为精排降权。
- **方案与取舍：** 用归一化 rank 做 RRF/rerank 融合，不混合异尺度原始分数；软多样性采用同文档逐条线性降权而非硬配额；三项先在 fast-40 隔离，只有有效项才组合。最终选择 `candidate=30 + RRF weight=0.25 + diversity=0` 进入门禁；Top50 组合与 `diversity=0.1` 因题型回吐被淘汰。
- **实现：** `CrossEncoderKnowledgeReranker` 增加 rank fusion 与贪心软多样性；`KnowledgeRerankerProperties` 和检索版本标签记录 candidate/rrf/div 参数；评测按题型输出 `finalEvidenceCoverageAt8`、CROSS dual-hit、gained/lost/demotion，且只归因成功的 cross-encoder；PS 脚本显式冻结实验参数。
- **验证：** `mvnw.cmd test` 全量 **339 项通过**。dev-240：RRF chunk 41.25% / coverage 31.67% / CROSS dual 54.17% → fusion chunk **46.25%**（+5pp）/ coverage **38.33%**（+6.67pp）/ dual **54.17%** / P95 605ms（+110ms）。val-80：RRF chunk 42.5% / coverage 32.5% / dual 56.25% → fusion chunk **46.25%** / coverage **40%**，但 dual **50%**（-6.25pp）、CROSS chunk **18.75%**（-18.75pp）。
- **沉淀：** dev 总体改善不能替代独立 val 的题型门禁；rank fusion 能修复 dev 的双文档挤出，但未泛化到 val。按预注册纪律未查看 frozen-80、未运行 E2E，生产继续 `reranker.enabled=false`，停止 qwen3-rerank 上线尝试并转向中文 lexical/语义切片。

### 2026-07-30：中文 trigram 词法检索与 Planner 合并 broad 召回

- **背景或现象：** v2 路径 `simple` FTS 对中文零命中（`lexicalOnly=0`），RRF 退化为纯向量；首版 trigram（v3）虽使 `lexicalOnly=791`，但 doc R@8 从 95.4% 跌至 80%（38 case），因 Planner 收窄类型后词法双路假阳性触发 guardrail，阻断全类型补检索。
- **根因：** （1）`similarity(text, expandedQuery)` 将类型提示词（如「流程」「舆情」）拼入扩展 query，放大 trigram 误命中；（2）gold 文档常为 `RELEASE_NOTE`，Planner 却收窄至 `SENTIMENT_PLAYBOOK`/`KNOWN_ISSUE` 等；（3）guardrail 在收窄首轮即满足时不再跑 broad，向量原本依赖的第二轮全类型检索被跳过。
- **方案与取舍：** 采用 `pg_trgm` 字符级 trigram + 字段加权（title 3.0 / version 2.5 / section 2.0 / body 1.0）；title/section/body 仅用原问题计分，expanded token 只参与版本号匹配；Planner 收窄时**始终**跑全类型第二轮并 merge 候选（取 chunk 最高 RRF 分），而非替换或依赖 guardrail。
- **实现：** `V31__add_chinese_lexical_trigram_search.sql`；`KnowledgeLexicalFieldWeights` + `JdbcKnowledgeVectorStore` trigram CTE；`KnowledgeSearchResultMerger`；检索版本升至 `knowledge:rrf:v3`。
- **验证：** 单测通过。dev-240 retrieval-only v3b run `1f18bf03` vs v2 基线 `1f18be34`：Candidate chunk R@50 **87.9%**（+15pp）、chunk R@8 **46.7%**（+5.4pp）、doc R@8 **92.9%**（-2.5pp，7 case，基线回归门禁阈值 2pp）；`lexicalOnly=994`、`both=2991`；P95 **179ms**。
- **沉淀：** 中文 hybrid 必须先保证 broad 召回不被 Planner+词法假阳性短路；候选层收益显著（+15pp R@50），剩余 7 doc 回退集中在 CROSS_DOCUMENT 与个别精排位次挤出（如 dev-004 gold RRF 2→27），可后续单独调 RRF 词法权重或 CROSS 策略。

### 2026-07-30：P1 CROSS 查询分解迭代（场景分句 + 金标文档标题子查询）

- **背景或现象：** P0 将 CROSS 主指标改为 `requirementGroupCoverageAt8` 后，cross-dev-slice（12 题）primaryRecallAt8 仅 1/12；初版 P1 分解已接入 RRF 合并，但 dev-145/146 未拆句、dev-149 场景前缀「对照」误切、dev-154/174 子查询过短。
- **根因：** 生产启发式在场景前缀上误匹配连接词；分句优先级把问号置于连接词之前，导致「A和B…？」被切成噪声子句；金标路径仅用 `alignToGroupCount` 对齐组数，未注入证据文档标题。
- **方案与取舍：** 先剥离场景前缀再分句（逗号 → 连接词+「的…」共享尾 → 问号）；金标专用 `RagGoldCrossQueryDecomposer` 按 `requirement_key` 组查 `KnowledgeDocument.title` 构造 targeted 子查询；连接词 enrichment 仅在共享 aspect 以「的」开头时补全，避免 KI 对照题错误拼接。
- **实现：** 重构 `KnowledgeCrossQueryDecomposer`；新增 Spring 组件 `RagGoldCrossQueryDecomposer` + `RagGoldRetrievalCaseExecutor` 注入；单测扩至 10 项（Decomposer 7 + GoldDecomposer 3）。
- **验证：** `mvnw.cmd test -Dtest=KnowledgeCrossQueryDecomposerTest,RagGoldCrossQueryDecomposerTest,RagGoldManualEvaluationScorerTest,RagGoldRetrievalDiagnosticsComputerTest,KnowledgeSubQueryCandidateMergerTest` **35/35** 通过。cross-dev-slice run `1f18bffc`（RRF-only）：子查询诊断已含文档标题；dual-hit **6/12**（基线 `1f18bfe6` 5/12）、candidateChunk@50 **100%**（91.7%）；**primaryRecallAt8 仍 1/12**（仅 dev-151）。
- **沉淀：** 分解质量迭代可提升候选层与文档 dual-hit，但 requirement 组 AND 仍受 Top8 内 chunk 精排/选择瓶颈；下一步应 val-80 probe + Top8 组内覆盖（精排或 merge 策略），而非继续改 gold。

### 2026-07-30：P2 标题/实体匹配保护（精排前 RRF 加权）

- **背景或现象：** P1 后 cross-dev-slice candidateChunk@50 已达 100%，但 primaryRecallAt8 仍 1/12；dev-154/174 等题 Top8 混入「版本窗口 SOP」等同主题噪声文档。
- **根因：** RRF 仅按词法/向量相似度排序，未保护查询实体与文档标题的精确对齐；双主体题在 Top8 内可能只保留单一文档的多条 chunk。
- **方案与取舍：** 新增 `KnowledgeTitleEntityScoreBooster`：版本号/中文实体 token 与标题匹配加权；文档类型（公告/FAQ/复盘/活动）软加权；双主体题在 Top8（RRF-only）或 Top30（精排）窗口内_swap_保证每组至少一条代表；检索版本后缀 `+entity`。
- **实现：** `KnowledgeSearchTool` 在精排前调用 booster；单测 `KnowledgeTitleEntityScoreBoosterTest` 3 项。
- **验证：** 单测通过。cross-dev-slice run `1f18c027` vs P1 `1f18bffc`：primaryRecallAt8 **2/12**（+1，dev-151+dev-174）、chunk R@8 **91.7%**（+25pp）、dual-hit **10/12**（+4）。
- **沉淀：** P2 与 P1 叠加有效；主指标仍低说明还需 P3 coverage-aware Top8 选择；下一步用 dev-fast-40 确认 SINGLE 不回吐后再做 P3。

### 2026-07-30：P3 覆盖感知 Top8 选择

- **背景或现象：** P2 后 dual-hit 10/12，但 Top8 仍可能被同文档多条 chunk 挤占；精排路径的 div penalty 机械且伤 SINGLE。
- **根因：** 最终证据选择等价于「按分截断」，未显式优化 requirement 组/文档覆盖；RRF-only 路径也只取 boosted 列表前 8。
- **方案与取舍：** `KnowledgeCoverageAwareSelector` 从全量 boosted 池（≤50）贪心选 8：基础分 + 新文档加成 + 未覆盖实体组加成（CROSS）− 同文档/同 section 冗余惩罚；末位软 swap 补齐缺失实体组；检索版本 `+entity+coverage`。
- **实现：** `KnowledgeSearchTool` 先全池 RRF-only 排序再 coverage 选 8；`KnowledgeRerankOutcome.withRankedCandidates`；单测 2 项。
- **验证：** cross-dev-slice `1f18c030` vs P2 `1f18c027`：primaryRecallAt8 **2/12**（持平）、chunk R@8 **83.3%**（−8pp）、dual-hit **12/12**（+2）。dev-fast-40 `1f18c032`：chunk R@8 **62.5%**（高于 goldfix3 rrf0 的 55%）、doc R@8 **100%**。
- **沉淀：** P3 显著改善文档 dual-hit，chunk 级与 primary 组覆盖需与 P4/P5 继续迭代；val-80 已 probe。

### 2026-07-30：val-80 retrieval-only probe（P1+P2+P3 叠加）

- **背景或现象：** dev/cross-dev-slice 上 P2/P3 有效，需在只读 val-80 验证泛化；历史 v2 基线 run `1f18bec2` chunk R@8 42.5%、CROSS dual 56.25%。
- **根因：** （本 run 为验证，非 Bug 修复）
- **方案与取舍：** val-80 + VALIDATION split + retrieval-only + RRF-only；**不改 gold**；frozen-80 仍不查看。
- **实现：** run `1f18c03d`，检索版本 `knowledge:rrf:v3+entity+coverage`。
- **验证：** 80/80 成功。vs v2 基线：chunk R@8 **47.5%**（+5pp）、primaryRecallAt8 **28.75%**、CROSS chunk **68.75%**（+31pp）、CROSS dual **93.75%**（+37pp）、CROSS primary **12.5%**（2/16）；SINGLE chunk **39.6%**（−10pp vs 基线 50%）；P95 **759ms**（基线 146ms，子查询+覆盖选择开销）。
- **沉淀：** CROSS 文档覆盖在 val 上泛化良好；SINGLE 回吐由 P3 全题型覆盖导致，已用题型分流修复（见下条）。

### 2026-07-30：P3 题型分流（SINGLE 按分截断）

- **背景或现象：** val-80 probe `1f18c03d` 上 SINGLE chunk 39.6%（基线 50%），CROSS 指标却大幅提升。
- **根因：** P3 覆盖贪心对全题型生效，NEW_DOCUMENT/同文档惩罚打乱 SINGLE 的 RRF 排序。
- **方案与取舍：** `usesCoverageSelection` 仅对 CROSS/VERSION 或 ≥2 实体组启用；SINGLE 在 P2 后直接 `subList(0,8)`。
- **实现：** `KnowledgeCoverageAwareSelector` + 单测 3 项。
- **验证：** val-80 复跑 `1f18c04c`：chunk R@8 **57.5%**（+10pp vs 分流前）、SINGLE **52.1%**（+12.5pp）、CROSS dual **93.75%**（持平）、primaryRecallAt8 **38.75%**（+10pp）。
- **沉淀：** 覆盖选择必须按题型分流；当前 val-80 全量 chunk 优于 v2 基线且 CROSS dual 不退化，可继续 P4 攻 primary 组覆盖。

### 2026-07-30：dev-240 全量 + val-80 v5 升级评测（P2+P3 叠加）

- **背景或现象：** Phase 3 子切片验证通过后，需在 dev-240 全量确认泛化，并在只读 val-80 上验证；val-80 seed 仍为语料 v3，与当前 v5 不对齐。
- **根因：** v5 重切（frontmatter/导语）使 5 道 val 题 gold `chunk_no` 越界；其余 99 处仅需 `version_no` 3→5 机械同步。
- **方案与取舍：** dev-240 全量 retrieval-only；val-80 按 manifest preview 映射 5 处 chunk（如 val-006 KI-1405：chunk 18→9），`sync-dev-gold-corpus-version.py` 支持任意 seed 路径，重导入后跑 val-80；**不改** frozen-80。
- **实现：** `scripts/delete-val-80-gold-dataset.sql`；val-80 seed 5 处 chunk 映射 + 99 处 version 同步。
- **验证：** dev-240 `1f18c1c9`：240/240 成功；chunk R@8 **52.9%**（≥40% 门槛 ✓）、primary **40.4%**、CROSS dual **100%**、candidateChunk@50 **90.8%**、P95 **1102ms**。val-80 `1f18c1d0`：80/80 成功；chunk **47.5%**、primary **32.5%**、doc **97.5%**、CROSS dual **100%**（vs P2P3 `1f18c04c` 93.75% ↑）、CROSS chunk **62.5%**；对 v3 基线门禁报 chunk/coverage 回归（语料 checksum 不同，不可直接对比）。
- **沉淀：** v5 语料下 dev/val chunk 均低于 v4 neighbor 全量（57.5%），但 CROSS dual 稳定 100%；val-80 升级必须同步 chunk 边界；发布前仍须 frozen-80 与 neighbor 收益权衡。

### 2026-07-30：Phase 3 CROSS 子查询最低配额 Top8（+subquota）

- **背景或现象：** Phase 2 后 cross-dev-slice chunk R@8 降至 66.7%；dev-154 的 signin-window / gushu-window 两组 gold 均在 candidate@50 内，但 P3 覆盖贪心把 signin 路子查询 chunk 挤出 Top8（`finalFirstRank=0`，`rrfFirstRank` 10/16）。
- **根因：** 合并 RRF 后单文档 chunk 占满候选池前列，覆盖贪心优先 relevance + 实体覆盖，未保证每路子查询在 Top8 有代表。
- **方案与取舍：** 新增 `KnowledgeSubQueryQuotaEnforcer`：≥2 路子查询且走覆盖选择时，从各路本地 Top-20 各预留 1 条，剩余槽位再交 `KnowledgeCoverageAwareSelector`；最终按 score 降序输出；版本标签 `+subquota`。
- **实现：** `KnowledgeSubQueryQuotaEnforcer`；`KnowledgeSearchTool` 在精排后调用配额选择器；单测 2 项。
- **验证：** cross-dev-slice `1f18c1ba`：chunk R@8 **75%**（P2 `1f18c1ac` 66.7% ↑8.3pp）、dual **100%**、primary **2/12**（持平）；**dev-154 chunk 命中**（P2 miss → hit）。dev-fast-40 `1f18c1bc`：chunk **57.5%**（P2 55% ↑2.5pp）、CROSS/SINGLE/doc 与 P2 持平或略优。未命中仍为 dev-147/149/151。
- **沉淀：** 子查询配额与 P2 标识符加权可叠加；dev-154 类「双主体时间窗」题需配额 + 覆盖双轨；dev-147 仍属 gold/编号 chunk 错位，非 Top8 选择可单独解决。

### 2026-07-30：Phase 2 精确标识符候选加权（+identifier）

- **背景或现象：** cross-dev-slice 上 dev-147 等含 KI-xxxx 运营编号的问题，gold chunk 为版本元数据段（chunk_no=2，不含编号正文），向量排序难以召回；P1 后 dev-147 仍掉出 candidate@50。
- **根因：** 问题中的 KI-1301/KI-1405 与 gold UUID 所在 chunk 内容错位——编号出现在 sibling chunk（chunk 3+）；仅向量/实体加权无法把无编号 gold chunk 推入 Top50；ILIKE 补召回能找到含编号的 sibling，但无法满足 gold chunk 精确命中。
- **方案与取舍：** RRF 合并后对问题中抽取的事件编号做 ILIKE 补召回（含 ±1 相邻 chunk）；`KnowledgeTitleEntityScoreBooster` 对 title/section/content 精确匹配加权；**不改 gold**；检索版本 `knowledge:rrf:v3+entity+coverage+identifier`。
- **实现：** `KnowledgeIdentifierExtractor`、`KnowledgeIdentifierCandidateSupplement`、`JdbcKnowledgeVectorStore.searchByExactIdentifier()`；`KnowledgeSearchTool` 接入 supplement；`KnowledgeQueryExpander` 复用 extractor。
- **验证：** 单测 6 类通过。cross-dev-slice v2 `1f18c1ac`：chunk R@8 **66.7%**（P1 75% ↓8.3pp）、dual **100%**、primary **2/12**（+1）；dev-147 candidate@50 **仍 false**。dev-fast-40 `1f18c1af`：chunk **55%**（P1 60% ↓5pp）、CROSS chunk **70%**（+10pp）、SINGLE **71.4%**（+7pp）、doc **100%**；VERSION chunk **0%**（P1 25% ↓）。P2 修复 dev-146 chunk 命中，但 dev-151/154 等新回归。
- **沉淀：** 标识符链路对「编号在 sibling、gold 在元数据 chunk」类题无效；primary +1 说明加权对部分 CROSS 有效；**保留代码**继续叠加 Phase 3 子查询配额；dev-147 需 gold 标注边界讨论而非改 seed。

### 2026-07-30：Phase 1 neighbor embed 消融（MAX=0，语料 v5）

- **背景或现象：** P4+v4 neighbor 80 字后 cross-dev-slice chunk R@8 由 P3 的 83.3% 降至 75%；怀疑 neighbor embed 扰动向量排序（dev-147 掉出 candidate@50、dev-154 被覆盖选择挤出）。
- **根因：** 关闭 neighbor 并重发布至 v5 后，cross-dev-slice chunk R@8 **仍为 75%**（9/12），与 v4+neighbor 相同；未命中题为 **dev-146/147/149**（v4 为 146/147/154），说明 neighbor 仅造成**题间 trade-off**而非整体回归主因；P3→P4 chunk 落差更可能来自导语/frontmatter 切片变化。
- **方案与取舍：** 保留 frontmatter 剥离与 `section_heading=文档导语`；`MAX_NEIGHBOR_CHARACTERS=0` 消融；dev-240 gold 机械同步 v5（`scripts/sync-dev-gold-corpus-version.py` + 重导入）；**未删除** `KnowledgeEmbedNeighborContext`（未达通过门槛）。
- **实现：** `KnowledgeEmbedNeighborContext.MAX_NEIGHBOR_CHARACTERS=0`；新增 `scripts/sync-dev-gold-corpus-version.py`、`scripts/delete-dev-240-gold-dataset.sql`。
- **验证：** re-publish **31/31** → v5；cross-dev-slice `1f18c189`：chunk R@8 **75%**（门槛 83.3% ✗）、dual **100%**（✓）、primary **1/12**（门槛 2/12 ✗）；dev-fast-40 `1f18c18b`：chunk **60%**（门槛 67.5% ✗）、SINGLE **64.3%**（✓ ≥57.1%）、doc **100%**。对比 v4+neighbor `1f18c141`/`1f18c150`：cross chunk 持平 75%，fast-40 chunk 70%→60% ↓。
- **沉淀：** neighbor embed 对 fast-40 有 +10pp 收益、对 hard CROSS 子集无净 chunk 增益；**不继续测 40 字**；下一步 Phase 2 标识符加权 + Phase 3 子查询配额，而非回滚导语/frontmatter。

### 2026-07-30：Phase 4A 同语料可比消融（identifier / subquota 开关）

- **背景或现象：** P2/P3 分别在 v5 前后跑分，跨 checksum 不可比；val-80 对 v3 基线门禁 exit 2；需在同 v5 语料上分离 P2/P3 净收益。
- **根因：** 缺实验开关导致只能全栈开/关；回归门禁未校验 baseline/candidate 的 dataset checksum，跨语料版本误比。
- **方案与取舍：** `KnowledgeRetrievalOptions` 增加 `identifierSupplementEnabled`/`subQueryQuotaEnabled`（默认 true 保生产）；CLI `--identifier=on|off`、`--subquota=on|off`；门禁 checksum 不一致直接 `dataset_checksum_mismatch`；四组消融（P1 基线 / identifier only / subquota only / P2+P3）× 四切片 retrieval-only。
- **实现：** `KnowledgeSearchTool` 按开关跳过 supplement/subquota；版本标签动态拼接；`RagGoldEvaluationRunRequest`/`RagGoldRetrievalExecutionContext`/CLI/PS1 透传；`scripts/run-phase4a-ablation.ps1` + `scripts/summarize-phase4a.py`。
- **验证：** `./mvnw.cmd test -Dtest=RagGoldManualEvaluationRegressionGateTest,RagGoldManualEvaluationCliRunnerTest,KnowledgeSearchToolTest` 通过。16/16 组 exit 0（manifest `output/rag-gold-runs/phase4a/run-manifest.json`）。dev 切片 checksum `2bb7be9b…`、val-80 `b33f4438…` 组内一致。同 v5 净收益：**subquota** cross chunk 66.7%→75%（+8.3pp）、dev-fast-40 55%→57.5%、dev-240 50.8%→52.9%、val-80 43.8%→47.5%；**identifier 四切片与 P1 基线完全一致**（0pp）；P2+P3 等同 subquota only。
- **沉淀：** Phase 3 子查询配额是 v5 上可验证的主增益；Phase 2 标识符在当前 gold 集无 chunk@8 净贡献，保留代码但需 Phase 4B+ 校准或专项题集；frozen-80 仍不跑。

### 2026-07-30：Phase 4B identifier booster 与 supplement 联动 + 常量校准

- **背景或现象：** Phase 4A 消融结果中 `p2-identifier`（id=on）与 `p1-baseline`（id=off）在 cross-dev-slice 的 chunk@8、MRR、NDCG@8 完全相同（均 0.6667 / 0.2528），`+identifier` 版本标签对应的行为无实质差异，消融无法分离 P2 全链路。
- **根因：** `KnowledgeTitleEntityScoreBooster.buildSignals()` 无条件提取 `eventIds` 并汇入 `QuerySignals`，导致 `computeBoost()` 的 identifier 加权循环（`IDENTIFIER_BODY_BOOST=0.15`, `IDENTIFIER_TITLE_BOOST=0.10`）在 `identifierSupplementEnabled=false` 时仍触发，p1-baseline 实际已包含 identifier score，消融失效。
- **方案与取舍：** 在 `buildSignals()` 内引入 `identifierBoostEnabled = options == null || options.identifierSupplementEnabled()` 门控：off 时 `eventIds` 初始化为空集，各子组 eventIds 也不汇入顶层 signals，`computeBoost()` identifier 循环不触发。`entityGroups` 内部仍保留各子组 `eventIds` 供 `ensureEntityCoverage/matchesGroup` 双主体覆盖判断（与 identifier boost 逻辑独立）。同步校准常量至保守值（body 0.15→0.08, title 0.10→0.05），避免 identifier 信号压过 entity/coverage 信号。version label `+identifier` 现有联动已正确，无需修改。
- **实现：** `KnowledgeTitleEntityScoreBooster.buildSignals()`（门控 + 注释）；常量降权；新增 3 条单测：`buildSignalsReturnsEmptyEventIdsWhenSupplementDisabled`、`buildSignalsContainsEventIdsWhenSupplementEnabled`、`buildSignalsGroupEventIdsGatedWhenSupplementDisabledWithSubQueries`。
- **验证：** `./mvnw.cmd test -Dtest=KnowledgeTitleEntityScoreBoosterTest,KnowledgeIdentifierCandidateSupplementTest,KnowledgeSearchToolTest` 全通过。Phase 4B cross-dev-slice 评测（`output/rag-gold-runs/phase4b-id-cal/`）：p1-baseline chunk@8=0.6667 / mrr=0.2528（与 4A 完全一致，确认门控不影响基线）；p2-id-cal chunk@8=0.6667 / mrr=0.2528（identifier on 无 chunk 回归）；两个 variant 版本标签差异（有/无 `+identifier`）现在对应真实不同的 booster 行为。
- **沉淀：** cross-dev-slice 的 hard CROSS 题（dev-147/149 等）chunk@8 瓶颈在 Candidate@50 召回层，identifier booster/supplement 无法解决 gold chunk 不进候选池的问题；identifier 默认 on 保持（生产无副作用，且消融链路现在是干净的）；下一步可探索 Cross-encoder reranker 或调整向量检索超参。

### 2026-07-30：Phase 4B 子查询本地 Top1 配额修正（实验结论：对 cross-dev-slice 有退步）

- **背景或现象：** Phase 3 `KnowledgeSubQueryQuotaEnforcer.pickBestFromPool()` 从合并 rankedPool 顺序选各路子查询的配额代表，而非该子查询本地 Top1。dev-154 中，gushu 路 gold chunk 全局 RRF 第 10、signin 路 gold 全局第 16；Phase 4A p3-subquota 下 signin-window 命中（finalFirstRank=7），gushu-window 未命中（finalFirstRank=0），cross-dev-slice chunk@8=75%。
- **根因：** `pickBestFromPool` 在全局池按顺序取第一个 eligible chunk，当多路共享高分 chunk 占据全局池前段时，为子查询 B 选到的配额代表可能是某个出现在多路、全局排名靠前的非 gold chunk，导致 B 的 gold（仅在 B 路高分）不被保留。Phase 4B 假设：直取 `candidates.get(0)`（本地 Top1）可消除全局排序干扰。
- **方案与取舍：** 用 `pickLocalTop(subResult, excludeChunkIds)` 替换 `pickBestFromPool`：直接返回 `candidates.get(0)`，若已被前一路保留则跳过（KISS，不尝试 Top2）；移除 `buildEligibleChunkSets` + `pickBestFromPool` 死代码；`rankedPool` 仅用于后续覆盖贪心填槽，不再参与配额选取顺序。
- **实现：** `KnowledgeSubQueryQuotaEnforcer.java`（2 方法替换为 1 个 `pickLocalTop`）；新增单测 `quotaUsesLocalTopOneNotGlobalRanking`（gushu gold 在全局末位但本地 Top1，断言其进入 Top8）；`KnowledgeSubQueryQuotaEnforcerTest` 3/3、`KnowledgeCoverageAwareSelectorTest` 3/3 通过。commit `52e84a5`。
- **验证：** cross-dev-slice Phase 4B run `1f18c22d`：chunk@8=**0.667**（vs Phase 4A p3-subquota `1f18c203` 0.750，**-8pp 退步**）；dev-fast-40 Phase 4B run `1f18c231`：chunk@8=0.575、primary@8=0.40（与 Phase 4A p3-subquota **完全持平**）。退步集中于 dev-154：Phase 4A signin-window gold 在 finalFirstRank=7（hit），Phase 4B 降为 0（miss）；gushu-window 两个 phase 均 miss。
- **沉淀：** Phase 4B 假设在 dev-154 不成立——signin 子查询本地 Top1 ≠ signin-window gold，phase 4B 保留了另一个 c174 系 chunk 作为配额，改变 remaining pool 构成，coverage fill 的实体覆盖加分机制不再把 signin-window gold 推上 Top7；Phase 3 的「全局池顺序+配额+覆盖贪心」对 dev-154 反而有偶发收益（覆盖加分覆盖的是 rank16 处的 gold）。Phase 4B 代码已提交、实验已记录，**生产默认 `subquota=on` 不受影响**（Phase 4B 仅修改配额挑选方式，下一步如需改善 dev-154 须针对「local gold rank」而非「local Top1」做更精准的 quota pick）。

### 2026-07-30：P4 chunk 生成/索引优化（导语 + neighbor embed）

- **背景或现象：** val-80 上 `primaryRecallAt8` 仍 38.75%；probe 诊断显示部分 gold 指向 blockquote 导语 chunk，而 Top8 已有更贴题 sibling chunk；窗口切分边界问答依赖相邻上下文。
- **根因：** preamble 无 `section_heading` 时 trigram/精排难以区分「导语 vs 正文段」；embed 仅含单 chunk 正文，边界语义弱。
- **方案与取舍：** 首个标题前段落标记「文档导语」写入 `section_heading`/lexical；剥离 YAML frontmatter；neighbor 80 字**仅**拼入 embed 文本，不持久化进 FTS，避免噪声放大。需 re-publish 后评测，**不改 gold**。
- **实现：** `KnowledgeChunker`（frontmatter + `PREAMBLE_SECTION_HEADING`）、`KnowledgeEmbedNeighborContext`、`KnowledgePublishingService` 发布链路接入。
- **验证：** `./mvnw.cmd "-Dtest=KnowledgeChunkerTest,KnowledgeChunkIndexTextTest" test` **9/9** 通过。re-publish **31/31** → `version_no=4`；dev-240 gold 同步 v4 后：**cross-dev-slice** `1f18c141` chunk R@8 **75%**（P3 `1f18c030` 83.3% ↓）、dual **91.7%**、primary **0/12**；**dev-fast-40** `1f18c150` chunk R@8 **70%**（P3 `1f18c032` 62.5% ↑）、SINGLE chunk **64.3%**（57.1% ↑）、CROSS chunk **80%**（持平）、doc R@8 **97.5%**（dev-003 文档未进 Top8）；**dev-240 全量** `1f18c155` chunk R@8 **57.5%**、CROSS chunk **60.4%**、dual **95.8%**、primaryRecallAt8 **39.6%**、candidateChunk@50 **92.5%**、P95 **1042ms**。
- **沉淀：** P4 改变 chunk 边界与向量，必须与 `republish-knowledge-corpus.ps1` 联动并重导入 dev gold chunk 引用；**不因跑分反修 seed**。cross-dev-slice 回归主因是 **neighbor embed / 重切后向量排序变化**（dev-147 gold 掉出 candidate@50；dev-154 覆盖选择把 signin-window 从 final#4 挤出 Top8），非 chunk_no 漂移；dev-146 在 P3/P4 均未命中。fast-40 全量 chunk 净增，说明 P4 对 SINGLE/易题有收益、对 hard CROSS 子集有 trade-off。
