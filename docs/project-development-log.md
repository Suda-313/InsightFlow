# InsightFlow 项目开发记录

> 最后更新：2026-07-25
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
