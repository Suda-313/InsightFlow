# InsightFlow Agent 优化 Todo

> 目标：将当前“具备 Agent 增强能力的舆情分析系统”逐步演进为有状态、可验证、可追溯、可闭环的舆情调查助手。
>
> 执行原则：先建立会话记忆、运行记录和评测基线，再增强 Prompt、数据 Tool、知识库和业务工作流；不为了“更像 AI”而优先引入多 Agent 或复杂 RAG。
>
> 能力主线：证据化调查 → 知识增强 → 异常自动调查 → 人工确认的处置副驾 → 人工纠错与评测闭环。Agent 不能直接写数据库或绕过业务服务，所有写操作必须经过权限、确认、审计和确定性 Command Service。

## 推荐执行顺序

```text
P0 基础安全与会话记忆
  → P1 Agent 可追溯与评测体系
  → P2 证据化调查能力
  → P3 企业知识库与 RAG
  → P4 业务闭环与协作能力
```

## P0：基础安全与会话记忆

- [x] 移除配置文件中的默认 API Key，仅从环境变量或受控部署配置读取。
- [x] 修复既有后端测试与当前生产接口的失配，恢复全量测试基线后再新增会话持久化功能。
- [x] 新增 `ChatSession`、`ChatMessage` 数据模型与 Flyway 迁移，所有记录按 `workspace_id` 隔离。
- [x] 新增会话 API：创建会话、查询会话列表、读取消息、发送消息、归档会话。
- [x] 前端支持新建会话、历史会话列表、刷新恢复和归档当前会话。
- [x] 实现短期记忆：每次提问读取最近 12 条最终消息，并限制单条历史文本长度。
- [ ] 对超长会话引入滚动摘要控制上下文长度（先观察真实 token 与会话长度，再决定摘要格式和触发阈值）。
- [x] 不持久化或展示模型原始思维链；只保存用户消息和最终回答。
- [x] 为会话 Workspace 隔离、消息恢复、模型上下文连续性补充单元测试；待补真实浏览器刷新 E2E 测试。

**验收标准：** 刷新页面后用户仍能看到并继续历史对话；追问“刚才提到的玩法 Bug”时，Agent 能理解此前语境。

## P1：Agent 可追溯与评测体系

- [x] 新增 `AgentRun` 运行记录：聊天、分类、情感、风险和报告 Agent 均通过同一服务记录 Prompt、模型和检索版本、脱敏输入摘要、最终输出、耗时、Token、错误和 Trace ID。
- [x] 建立 LLM 延迟与 Token 基线：按 Agent、Prompt 版本和模型记录最近 100 条成功调用的 p50/p95 耗时和输入/输出 Token；在评测集不退化的前提下，后续优先压缩上下文并限制无效长输出。
- [x] 建立 Prompt 模板与版本管理：聊天、分类、情感、风险和报告 Agent 均通过版本化模板或目录获取提示词，LLM 日志记录 `prompt_version`。
- [x] 建立首版金标评测集，覆盖 30 个趋势、告警、比较、拒答和报告问题；每题定义固定脱敏场景、必须事实和禁止编造项。
- [x] 实现评测运行器，输出必要事实覆盖率、禁止断言命中率（幻觉代理）、拒答合规率、回答具体性代理、延迟和 Token 成本，并持久化每题的规则变化。当前没有真实数据来源引用，不将事实覆盖率伪称为引用覆盖率；引用覆盖率随 P2 Tool 接入后实现。
- [x] 为 Prompt、模型和检索策略变更设置“评测结果不退化”的门槛：同数据集版本的历史批次可比较，质量退化返回固定违规码；延迟与 Token 同时展示但不以牺牲质量换成本。

- [x] 为 RAG 评测补齐逐题开始/终态、检索/生成耗时和失败阶段日志；供应商未返回 Usage 时显式记录 unavailable，不伪造 Token。
- [x] 为长耗时 RAG 评测增加应用级单题超时与异步任务化（持久化状态、可轮询结果）；任一题失败即标记 `partial_failed`，不写入成功基线。
- [x] 为模型 HTTP 客户端增加网络读超时；读取上限小于单题应用层兜底，避免底层调用长期占用 Worker。
- [ ] 排查 DashScope 生成阶段的连续超时：已将默认 HTTP 读超时提升至 110 秒、单题超时 120 秒、任务租约 720 秒；待全部题目成功执行后重跑完整 RAG 质量基线。若本地仍配置 `AGENT_HTTP_READ_TIMEOUT_SECONDS=50` 等旧值，需同步删除或调大。
- [x] 修复评测页刷新后的进行中状态恢复与过程展示：RAG 评测按 Workspace 隔离查询最近任务并恢复轮询，展示总题数、已完成题数、当前受控阶段、终态汇总、累计耗时和失败阶段；`partial_failed` 仍不得混入可比较的 RAG 历史基线，也不展示模型原文。
- [x] 将金标评测改为与 RAG 一致的持久化异步任务与过程展示：`/evaluations/gold` 改为复用既有 `AsyncTask` 租约/轮询机制，持久化已完成题数、单题终态、累计耗时和受控失败状态；不改变题集或评分规则。
- [ ] 若需要“意图识别准确率”，先建立独立人工标注的意图测试集和混淆矩阵；当前只有规则路由实现与问答/RAG 指标，不能将其表述为已测准确率。



### 待办：RAG 检索与切分优化（2026-07-28）

> **设计文档：** [`docs/superpowers/specs/2026-07-28-rag-optimization-design.md`](superpowers/specs/2026-07-28-rag-optimization-design.md)  
> **规模假设：** 语料扩展到 **50～100 篇**（约 200～1000 chunk）；**不换**独立向量库 / Cross-encoder（除非 Phase R4 评测证明需要）。  
> **与评测待办分工：** 本文档管 Chunker/检索/索引；下节「RAG 评测贴近真实业务」管题集、指标口径、对外表述。两线应 **并行**，R1 与评测扩充同步做。

#### Phase R1 — 切分与 embed 前缀（语料 <30 篇，优先）

- [x] **Markdown 标题切分：** 按 `#～###` section 切分，超长再字符窗口切；更新 `KnowledgeChunker` + 单测。
- [x] **Embed 上下文前缀（必选）：** 嵌入前拼 `[标题 | TYPE | vN]` + 章节标题；`knowledge_chunk.content` 存正文。
- [x] **评测扩至 15 题（2026-07-28）：** `RagEvaluationFixtureFactory` 由“每类型固定一题”改为按 `(类型, 文档新旧序号)` 定位的模板集合；同一文档可有多道章节级题目，第二篇同类型文档自动补出跨文档混淆题。语料补齐后（每类型 2 篇已发布文档）产出 14 道题 + 1 道无依据题 = 15 题，dataset_version 随实际发布文档变化；单文档小 Workspace 会按可用题数退化，不会伪造题目。跨文档混淆题：`release-note-3`（次新版本公告）、`known-issue-3`（历史归档已知问题）、`support-sop-3`（FAQ）、`sentiment-playbook-3`（玩法机制参考），共 4 道，超过“≥2”验收线。
- [x] **（可选）overlap ~100 字：** 仅窗口硬切时启用。

#### Phase R2 — 检索与证据（30～60 篇）

- [ ] **Top8 按 document 限流：** 每个 `document_id` 最多 2 chunk 进最终证据。
- [ ] **中文 FTS 补强：** `pg_bigm` 或 keywords 列 + GIN（择一实现）。
- [ ] **收紧补检索：** 首轮证据已足则不再第二次全类型检索。
- [ ] **证据片段 300→500 字 + 标题/vN/chunk_no 展示。**
- [ ] **评测扩至 20+ 题。**

#### Phase R3 — 规模运维（60～100 篇）

- [ ] **ivfflat lists 调参 + 全量 re-publish 后 REINDEX SOP。**
- [ ] **chunk 窗口 800/1000/1200 评测定稿。**
- [ ] **评测扩至 30 题作为回归门禁。**
- [ ] **（可选）EXPIRED 版本 chunk 清理。**

#### Phase R4 — 仅评测仍不达标（默认不做）

- [ ] 评估：单次查询改写 / 换 embedding / Cross-encoder 重排（三选一，需 Flyway 或延迟评估）。

**验收标准（R1）：** 标题切分 + 前缀上线；≥15 题评测可复跑；召回或引用指标一项升 ≥10% 且另一项不降。

---

### 待办：运营调查型 RAG 语料与人工金标评测（2026-07-28）

> **业务目标：** RAG 服务于舆情运营、版本复盘和决策调查，不再以客服 FAQ 为主。现有 `docs/knowledge-sources/` 的 9 篇材料与动态 Fixture 只保留为链路回归样例，不能作为正式质量基线，也不能把其中未经外部核验的游戏描述表述为事实。
>
> **语料边界：** 正式材料必须记录来源 URL、整理日期、适用版本/时间范围、负责人以及事实/推断边界；优先覆盖版本与热修档案、活动/运营事件、舆情事件复盘、游戏机制与术语、分析口径/SOP 和数据限制。首期目标为 **50～80 篇、400～800 个 chunk**，以真实的版本冲突、长文多章节和相似事件建立检索难度，而不是为凑字数重复写作。
>
> **评测边界：** 以版本化人工金标数据集取代按已发布文档动态生成的生产评测题。题目必须记录问题、类型、难度、Workspace 范围、可接受 document/version/chunk 证据集、关键事实、禁止断言和 `should_refuse`；不得只存一篇“标准答案”全文。

#### G1 — 数据集与标注契约（先做）

- [x] 新建不可变 `rag_gold_dataset`、`rag_gold_case`、`rag_gold_case_evidence`、`rag_gold_case_assertion` 领域模型与前向迁移（V27）；`RagGoldDatasetCommandService` / `RagGoldDatasetReadService` + 快照 DTO；题目通过公开 ID 指向可接受的 document/version/chunk。
- [x] 将 `RagEvaluationFixtureFactory` 收敛为测试 Fixture / 语料健康检查；生产评测改为加载指定、已发布的人工金标数据集版本。
- [x] 定义并落地首批 **100 条 development 金标**（已被 ops-rag-v1 **400 题** supersede；dev-240 / val-80 / frozen-80 已 import 进库）。
- [x] 定义开发、验证、冻结三种 split：开发集允许调参；验证集仅用于候选方案比较；冻结集不暴露关键事实或逐题失败正文，只在发布前运行。（ops-rag-v1 dev-240 / val-80 / frozen-80 + seed schema + import profile）

#### G2 — 评分、运行与展示

- [x] 评分器分别计算 document/chunk `Recall@1/@3/@8`、MRR/nDCG、关键事实覆盖率、引用支撑率、禁止断言命中率、无依据拒答合规率（`RagGoldManualEvaluationScorer` + Runner + PS 脚本）。
- [ ] 异步评测任务冻结数据集版本、题目 ID 列表、证据集版本、Prompt、模型、embedding 与检索版本；语料变动不得覆盖旧数据集或历史结果。
- [ ] 评测页展示数据集版本、split、题数和各分项指标；冻结集只显示聚合结果与受控状态，不展示可用于反向调参的完整金标。
- [x] 建立 **400 条**人工金标（dev-240 / val-80 / frozen-80）并 import 进库；dev-240 全量 240/240 执行成功（批次 `1f18b3a3-43bb-6046-b7d2-7124cd3c9991`）；**待跑** val-80 / frozen-80 基线。

#### G3 — 面向运营调查的语料扩展

- [x] 新增 `OPERATION_EVENT`（版本/活动/维护/渠道策略等时效事实）与 `POSTMORTEM`（已完成事件的证据化复盘）文档类型；以新 Flyway 演进约束，原有四类保持兼容。
- [x] 建立首批 15～20 篇运营调查语料（`docs/knowledge-sources/` 新增 19 篇）；**已上传发布**（31 篇 / 441 chunk，manifest 已导出）。
- [ ] 扩充至 50～80 篇正式语料后，按冻结集重跑基线，再决定 R2 的 Top8 文档限流、中文 FTS、补检索和证据片段改动。

**验收标准：** 首期 100 条人工 development 数据集可复跑，能分别解释文档/Chunk 召回、事实、引用、禁止断言和拒答指标；最终 400 条数据集含独立冻结集。任一 Prompt、切分、检索或模型调整必须在冻结集关键质量指标不退化的前提下发布。

#### G4 — dev-240 基线后三步优化执行方案（2026-07-29）

> **基线事实（dev-240，240/240 succeeded）：** Recall@8 ≈ 32%、事实覆盖 ≈ 31%、引用正确 ≈ 99.8%、无依据回答 ≈ 22%、REFUSAL 合规 1/5。  
> **前置结论：** 当前部分聚合指标不可直接用于调参（拒答率分母错误、carry-forward 近似 MRR/nDCG、文档级 Recall 与 chunk 级相同、断言子串匹配偏严）。**必须先完成 Step M（度量修复），再动检索与 Prompt。**

##### Step M — 度量修复（不改检索/Prompt，1～2 天）

| 序号 | 任务 | 改动点 | 验收 |
|------|------|--------|------|
| M1 | 拒答合规率分母 | `RagGoldManualEvaluationScorer.aggregate`：分子改为 `shouldRefuse && refusalCompliant` | 全 REFUSAL 批次合规率 ∈ [0,1]；dev-240 应显示 **0.2** 而非 47.2 |
| M2 | 文档级 Recall 独立 | `RagGoldEvidenceMatcher.matchesEvidence`：DOCUMENT 粒度只比 `documentPublicId` 前缀 | 存在「文档命中、chunk 未命中」题时，doc Recall@8 > chunk Recall@8 |
| M3 | 断言匹配升级 | `RagGoldAssertionMatcher`：短断言子串；长断言 bigram + CJK 字覆盖率 ≥60% | 单测通过；**待全量 dev-240 重跑**验证事实覆盖率提升 |
| M4 | carry-forward 无损化 | `RagGoldManualEvaluationCaseResult` 增 hit@K/MRR/nDCG；历史 JSON 缺字段回退近似 | 单测通过；新批次 JSON 含完整字段 |
| M5 | 耗时字段合并 | 增 latency 可空字段 + `latencySampleCount`；分位跳过 null | 单测通过；**待 `--retry-from-run` 验证** |
| M6 | 回归测试 | Scorer / CarryForward / EvidenceMatcher / AssertionMatcher 测试 | [x] `./mvnw.cmd test -Dtest=...` 通过 |

**Step M 完成标志：** 对同一 dev-240 快照 **全量重跑**（非 carry-forward），产出批次 `baseline-metrics-v2`；JSON 中拒答率、Recall@3≠Recall@8（若有差异题）、latency P95 > 0。事实覆盖率新数字作为 Step R 的对比基线。  
> **状态（2026-07-30）：** M1–M6 代码与单测已完成；**baseline-metrics-v2 全量端到端尚未重跑**（上一轮 carry-forward 批次仍为 Step M 前度量）。

##### Step R — 候选召回 + 专用精排（修订版，检索版本 `knowledge:rrf:v2`）

> **为什么修订：** 原方案把“候选 Top40 + 每文档最多 2 chunk”直接当成最终排序，缺少 Candidate Recall@30/50，无法判断正确 chunk 是没被召回还是只排得太后；硬性每文档限 2 条还会伤害需要同一 SOP/复盘多个章节的题。  
> **本轮决策：** 先证明正确证据已进入候选集，再上 Cross-encoder / 专用 reranker；最终阶段使用**软多样性惩罚**，不采用绝对每文档 2 条上限。Prompt 保持 `chat:v4`，隔离检索贡献。

###### R0 — 建立低成本检索评测漏斗（先做，避免反复跑 240 次 LLM）

- [x] 1. Runner `retrieval-only` 模式（`RagGoldRetrievalCaseExecutor` + CLI `--mode=retrieval-only`）
- [x] 2. 固定子集 `--case-keys-file` + `evaluation/rag/gold/slices/dev-fast-40.txt` / `dev-e2e-30.txt`
- [x] 3. retrieval-only 输出 Candidate Recall@10/30/50、双文档命中率、候选来源计数（`RagGoldRetrievalFunnelAggregate`）
- [x] 4. 评测 embedding 磁盘缓存（`RagGoldEvaluationEmbeddingCache`，键 checksum+model+questionHash）
- [ ] 5. 精排候选 ID/分数缓存（R2 前再补；checksum/SQL 变更须失效）
- [x] **已跑 L2（2026-07-30）：** `dev-fast-40` retrieval-only，run `1f18bc46`；240/240 成功；P50=313ms P95=495ms；Candidate doc R@30 **100%**、chunk R@50 **67.5%**；`candidateSourceLexicalOnly=0`（见 R1 词法待办）

**固定分层：**

| 层级 | 运行内容 | 使用时机 |
|------|----------|----------|
| L1 | 单元测试 | 每次代码改动 |
| L2 | `dev-fast-40` retrieval-only | 每轮检索参数调整 |
| L3 | `dev-240` retrieval-only | 快速子集确认提升后 |
| L4 | `dev-e2e-30` 端到端 | 检索候选方案定稿后 |
| L5 | 完整 dev-240 → val-80 → frozen-80 | 检索版本/Prompt/模型准备发布时 |

**建议固定子集构成：**

| 子集 | SINGLE | CROSS | VERSION | OPERATION | WORKSPACE | REFUSAL |
|------|-------:|------:|--------:|----------:|----------:|--------:|
| dev-fast-40 | 14 | 10 | 8 | 5 | 3 | 0 |
| dev-e2e-30 | 4 | 10 | 8 | 5 | 0 | 3 |

子集必须同时包含当前失败题和已成功题，避免只拟合失败样本。

###### R1 — 修候选生成并测候选上限（不做精排）

- [x] 1. `JdbcKnowledgeVectorStore` lexical/vector Top40 → RRF Top50；`KnowledgeSearchTool` 最终仍 Top8
- [x] 2. 词法可见性：`V29` 增 `section_heading`/`lexical_text`；发布写入；SQL 对旧切片回退 title/type/version/content
- [x] 3. `KnowledgeQueryExpander`（版本号/事件编号/日期/类型提示词）
- [x] 4. `KnowledgeRetrievalPlanner` 关键词扩充
- [x] 5. **中文词法改用 pg_trgm（2026-07-30）：** `V31` 启用 trigram GIN；`KnowledgeLexicalFieldWeights` 对 title/section/version/body 加权；Planner 收窄类型时 merge broad 候选，避免词法假阳性阻断第二轮全类型检索
- [x] **已跑 L3（2026-07-30）：** `dev-240` retrieval-only，run `1f18bc4b`（checksum `96533fe9…`，语料 v2 + 金标重导入后）；Candidate doc R@30 **97.1%**、chunk R@30 **66.3%**、chunk R@50 **71.7%**；P95 **452ms**；**R1 候选门槛已达标**（精排 R2 可排期；词法 hybrid 仍待修）
- [x] **已跑 L3-trigram（2026-07-30）：** `dev-240` retrieval-only v3b，run `1f18bf03` vs v2 基线 `1f18be34`：Candidate chunk R@50 **87.9%**（+15pp）、chunk R@8 **46.7%**（+5.4pp）、doc R@8 **92.9%**（-2.5pp，7 case）；`lexicalOnly=994`、`both=2991`；P95 **179ms**

**R1 门槛：**

- Hybrid Candidate document Recall@30 ≥ 75%；
- Hybrid Candidate chunk Recall@30 ≥ 60%，Recall@50 ≥ 70%；
- retrieval P95 < 3 秒；
- 若 Candidate Recall@50 仍低，继续修召回，**不得用精排掩盖候选缺失**。

###### R2 — 引入可回退的专用精排

- [x] 1. `KnowledgeReranker` + `RrfOnlyKnowledgeReranker`（默认/回退）
- [x] 2. `CrossEncoderKnowledgeReranker` + `DashScopeKnowledgeRerankGateway`（qwen3-rerank，RRF Top30 输入）
- [x] 3. `KnowledgeSearchTool` 接入精排；`SearchCandidate` 扩展 documentType/sectionHeading/effectiveWindow
- [x] 4. 配置 `insightflow.knowledge.reranker.*`（默认 **enabled=false**）
- [x] 5. 评测 CLI `--reranker=on|off` + PS 脚本 `-Reranker`
- [ ] **待跑：** dev-fast-40 retrieval-only，`--reranker=on` vs off 对比 Recall@8/MRR/P95
- [ ] **待跑：** 达 R2 生产门槛（Recall@8 +5pp 或 MRR +0.08；P95 增量 ≤1.5s；val/frozen 不退化）

###### Phase A/B — 评测可信度与 evidence requirement groups（2026-07-30）

**Phase A — 评测可信度（P0）**

- [x] A1 移除 `KnowledgeRerankerSelector` ThreadLocal；精排仅经 `KnowledgeRetrievalOptions.withReranker`
- [x] A2 E2E / retrieval-only 均显式传播 reranker；CLI `--reranker=on|off` 对全量 E2E 生效
- [x] A3 单题诊断写入 `caseResults.retrievalDiagnostics`（RRF rank、候选/精排 rank、Top8 公开 ID 等）
- [x] A4 聚合指标增 `finalEvidenceCoverageAt8`、`finalCrossDocumentDualHitAt8`、`rerankFallbackRate`、rerank P50/P95、`chunkRecallMetricMode`
- [x] A5 单测：Top30 外 gold、精排降权、fallback、requirement 组 partial hit、E2E rerank 传参
- [x] A6 `ChatService` 使用 `knowledgeSearchTool.resolveRetrievalVersionLabel(null)`
- [x] **已跑（2026-07-30）：** dev-fast-40 retrieval-only run `1f18bde3`（语料 v3 + 金标 checksum `bf1968f0…`）；`retrievalDiagnostics` 已写入；`finalEvidenceCoverageAt8` **25%**、`chunkRecallAt8AnyEvidence` **42.5%**、Candidate chunk R@50 **67.5%**、P95 **490ms**
- [ ] **待跑：** dev-fast-40 E2E 重跑（retrieval-only 基线已就绪）

**Phase B — gold evidence 模型（P0）**

- [x] B1 schema / seed 可选 `requirement_key`；Flyway V30 + 实体/快照/导入/checksum
- [x] B2 `RagGoldEvidenceMatcher` 组内 OR / 组间 AND；Scorer 计算 `finalEvidenceCoverageAt8`
- [x] B3 dev-fast-40 多证据题已标注 requirement_key（dev-002 OR、dev-151 AND、CROSS/VERSION 分组）
- [x] B4 已从 17 篇 `docs/knowledge-sources/*.md` 删除「评测锚点」节
- [x] **已做（2026-07-30）：** 语料 re-publish 31/31 → v3（424 chunk）；manifest 导出；seed `version_no` 升 3 + 10 处末尾 chunk 修正（`scripts/fix-seed-chunk-refs.py`）；三 split 重导入
- [x] B5 Matcher / Scorer 单测：OR 组、dev-151 式 partial vs full coverage

**Phase C — 回归门禁与 R2 A/B（2026-07-30）**

- [x] C1 `RagGoldManualEvaluationRegressionGate` 接入 `finalEvidenceCoverageAt8`（±2pp 容忍）；单测覆盖 partial-only 退化
- [x] C2 val/frozen 已随 v3 re-import（checksum `2a1cb2eb…` / `3c1d81d2…`）
- [x] C3 dev-fast-40 retrieval-only A/B（语料 v3 + 金标 `bf1968f0…`）：
  - RRF run `1f18bde3`：chunk R@8 **42.5%**、finalEvidenceCoverage **25%**、MRR **0.235**、P95 **490ms**
  - rerank run `1f18be30`：chunk R@8 **47.5%**（+5pp）、finalEvidenceCoverage **25%**、MRR **0.265**、rerank P50 **254ms**、P95 **397ms**
- [x] C4 dev-240 retrieval-only 全量 A/B：
  - RRF run `1f18be34`：chunk R@8 **41.3%**、finalEvidenceCoverage **31.7%**、Candidate chunk R@50 **72.9%**、P95 **495ms**
  - rerank run `1f18be38`：chunk R@8 **47.1%**（+5.8pp）、finalEvidenceCoverage **38.3%**（+6.7pp）、MRR **0.250**、rerank P50 **255ms**、P95 **424ms**
- [ ] **R2 生产仍不启用：** dev-fast-40 上 CROSS dual-hit@8 精排后 **60%**（RRF 70%）；val-80 / frozen-80 精排对比未跑；MRR 增量未达 +0.08 门槛

**Phase C 跟进（未做）：** dev-fast-40 E2E 重跑；val/frozen 精排对比；回归门禁 baseline 持久化到 DB run

**Phase D — rerank 集合选择修复（已完成门禁，2026-07-30）**

- [x] 按题型聚合 `finalEvidenceCoverageAt8`、CROSS dual-hit 与 rerank gained/lost/demotion；fallback 不计入精排归因
- [x] 支持独立实验 Top50、RRF rank fusion、Top8 同文档软多样性；检索版本标签记录 `input/rrf/div` 参数
- [x] 单测覆盖 Top50 输入、RRF 强锚点保护、第二文档保留、fallback 与诊断聚合
- [x] fast-40 隔离实验选出 `input=30 + RRF weight=0.25 + diversity=0`；Top50 组合使 VERSION_CONFLICT 回吐，`diversity=0.1` 降低总体 chunk Recall，均淘汰
- [x] dev-240 通过：chunk R@8 +5pp、coverage +6.67pp、CROSS dual-hit 不下降、retrieval P95 +110ms
- [x] val-80 门禁否决：总体 chunk +3.75pp、coverage +7.5pp，但 CROSS dual-hit -6.25pp、CROSS chunk -18.75pp
- [x] 按门禁纪律未查看 frozen-80、未运行 dev-e2e-30；生产保持 `enabled=false`，停止 qwen3-rerank 上线尝试

**Phase D 跟进 — v3b + probe-15 精排小步验证（2026-07-30）**

- [x] `rerank-probe-15` slice：RRF-only **0/15** → `rrf0` **8/15**（gained 8）、`fusion0.25` **7/15**（dev-212 回吐）、`div0.05` **6/15**（CROSS dual 4/4 但 SINGLE 回吐 dev-054/060）
- [x] dev-fast-40 + v3b + rerank（`in30:rrf0:div0`）run `1f18bf2f`：chunk R@8 **50%**、doc R@8 **100%**、CROSS dual **70%**、finalEvidence **25%**、rerank P50 **250ms**、P95 **526ms**
- [x] 对比脚本 `scripts/compare-rerank-probe-variants.py`
- [x] **probe 结论：** 在本切片上 **纯 rerank（rrf0）优于 fusion/div**；CROSS chunk 仍 0/4，dual-hit 有提升但 gold chunk 未进 Top8
- [x] **dev-015/016 诊断（2026-07-30）：** 非精排格式问题；gold 指向 chunk 2「导语 blockquote」，精排正确优先同文档 chunk 3/6/7（015）与 chunk 3 含 P99（016，已在 Top8 但 chunk_id 不匹配）
- [x] **金标已修（2026-07-30）：** dev-015/016 evidence `chunk_no` 2→3；dev-240 重导入 checksum `f030f79f…`；probe rerank `1f18bf68`：**9/15**（+1），dev-016 **hit**；dev-015 仍 miss（gold chunk 3 RRF **13**）
- [x] **dev-015 OR 金标（2026-07-30）：** chunk 3 + chunk 7 同 `requirement_key=maintenance-dev015`；probe rrf0 run `1f18bf8b`：**10/15**
- [x] **dev-151/219 金标（2026-07-30）：** 151 OR chunk4+7；219 chunk2→16；probe `1f18bf9a`：**12/15**
- [x] **cross-miss-3 精排探针（2026-07-30）：** in50/div0.05/fusion0.25 均 **未**提升 chunk R@8（根因：Top8 有更佳 chunk 但 gold 仍指导语段）
- [x] **dev-154/174/183 OR 金标（2026-07-30）：** 154/174 暑期 chunk12/13 + 古蜀 chunk3 OR；183 档案 chunk17 + 1.4.2 chunk4 OR；checksum `f4ea2cac…`；probe `1f18bfa8`：**15/15** chunk R@8
- [x] **dev-fast-40 复验（goldfix3）：** run `1f18bfaa` chunk R@8 **55%**（+5pp）、CROSS chunk **60%**（+10pp）、finalEvidence **30%**（+5pp）；CROSS dual 仍 **70%**

**Phase E — 评测主指标修复 + CROSS 查询分解（2026-07-30）**

**P0 — 评测主指标按题型分流（代码已完成，待 L3 验证）**

- [x] SINGLE 题主指标仍为 any-evidence `chunkRecallAt8`；CROSS/VERSION 主指标为 `requirementGroupCoverageAt8`（=`finalEvidenceCoverageAt8`）
- [x] 单题 `retrievalDiagnostics.requirementGroups[]` 记录每组 `rrfFirstRank` / `finalFirstRank` / `satisfiedAt8`
- [x] 聚合增 `primaryRecallAt8`、`requirementGroupCoverageAt8`；`byQuestionType` 增 `primaryMetricName` + `primaryRecallAt8`
- [x] `rerank-probe-15.txt` 标注为 **frozen regression slice**（只读 gold，不因跑分改 seed）
- [x] 新建 `evaluation/rag/gold/slices/cross-dev-slice.txt`（12 道代表性 CROSS，源自 dev-240）
- [x] 文档约定：**val-80 只读 gold**；**frozen-80  untouched**
- [x] **L3 验证：** run `1f18bfe6`（cross-dev-slice, rrf0 in30）；`primaryRecallAt8` **1/12**（8.3%）、`chunkRecallAt8AnyEvidence` **8/12**（66.7%）、dual-hit **5/12**；仅 dev-151 全组满足；`requirementGroups[]` 漏斗可用
- [x] **P1 单测：** 31/31 通过（Scorer/Diagnostics/Decomposer/Merger/SearchTool 等）

**P1 — CROSS 查询分解检索（迭代已完成）**

- [x] `KnowledgeCrossQueryDecomposer`：场景前缀剥离 → 逗号 → 连接词 → 问号分句；连接词共享问句仅补「的…」结构尾
- [x] `RagGoldCrossQueryDecomposer`：按 `requirement_key` 组 + 文档标题构造 targeted 子查询（Spring 组件，接 `KnowledgeDocumentRepository`）
- [x] 各子查询独立 lexical+vector → `KnowledgeSubQueryCandidateMerger` RRF 合并 → 既有 rerank Top8
- [x] `KnowledgeRetrievalDiagnostics` / case 诊断写入 `subQueries[]`、`candidatesPerSubQuery[]`
- [x] 单测：`KnowledgeCrossQueryDecomposerTest`（7）、`RagGoldCrossQueryDecomposerTest`（3）、Scorer/Diagnostics/Merger 等 **35/35** 通过
- [x] **L3 cross-dev-slice P0/P1（`1f18bfe6`）：** 初版分解质量参差；primaryRecallAt8 **1/12**
- [x] **P1 迭代 L3（`1f18bffc`，RRF-only）：** 子查询已含文档标题；dual-hit **6/12**；**primaryRecallAt8 仍 1/12** — 瓶颈在 Top8 组内 chunk 选择
- [x] **P2 标题/实体保护（`KnowledgeTitleEntityScoreBooster`）：** 精排前 RRF 加权 + 双主体 Top8/Top30 覆盖；检索版本 `knowledge:rrf:v3+entity`
- [x] **P2 L3（`1f18c027`）：** primaryRecallAt8 **2/12**、chunk R@8 **91.7%**、dual-hit **10/12**
- [x] **P3 覆盖选择 Top8（`KnowledgeCoverageAwareSelector`）：** 贪心综合 relevance + 同文档冗余 + 实体覆盖增量；版本 `+entity+coverage`
- [x] **P3 L3 cross-dev-slice（`1f18c030`）：** primaryRecallAt8 **2/12**（持平）、chunk R@8 **83.3%**、dual-hit **12/12**（100%）
- [x] **dev-fast-40 L2（`1f18c032`）：** chunk R@8 **62.5%**、doc R@8 **100%**、CROSS chunk **80%**、SINGLE chunk **57.1%**
- [x] **P3 题型分流：** SINGLE 等仅 P2 加权后按分截断；CROSS/VERSION 或 ≥2 实体组才走覆盖贪心
- [x] **val-80 复跑（`1f18c04c`，分流后）：** chunk R@8 **57.5%**（v2 42.5%）、SINGLE chunk **52.1%**（分流前 39.6%）、CROSS dual **93.75%**、primaryRecallAt8 **38.75%**

**Phase 1 — neighbor embed 消融（2026-07-30，未达通过门槛，保留代码）**

- [x] **变体 A（MAX=0）：** re-publish → v5；dev-240 gold 同步（checksum `2bb7be9b…`）
- [x] **cross-dev-slice `1f18c189`：** chunk R@8 **75%** ✗、dual **100%** ✓、primary **1/12** ✗
- [x] **dev-fast-40 `1f18c18b`：** chunk **60%** ✗、SINGLE **64.3%** ✓、doc **100%**
- [x] **结论：** neighbor 非 CROSS chunk 回归主因；关闭后 fast-40 回落；**不删** `KnowledgeEmbedNeighborContext`、**不测 40 字**
- [x] **Phase 2：** 精确标识符候选加权（dev-147 等）
- [x] **Phase 3：** CROSS 子查询最低配额 Top8（dev-154 等）

**Phase 3 — CROSS 子查询最低配额 Top8（2026-07-30）**

- [x] **实现：** `KnowledgeSubQueryQuotaEnforcer` — 多路子查询各路 Top20 各锁 1 条，剩余槽位交 P3 覆盖贪心；版本 `+subquota`
- [x] **单测：** `KnowledgeSubQueryQuotaEnforcerTest` 2/2 + 既有 P3 测试通过
- [x] **cross-dev-slice `1f18c1ba`：** chunk R@8 **75%**（P2 66.7% ↑）、dual **100%** ✓、primary **2/12**（持平）；**dev-154 chunk 命中** ✓
- [x] **dev-fast-40 `1f18c1bc`：** chunk **57.5%**（P2 55% ↑）、CROSS **70%**、SINGLE **71.4%**、doc **100%**
- [x] **结论：** 子查询配额修复 dev-154 类覆盖挤出；dev-147/149/151 仍 miss；P2+P3 叠加保留

**Phase 2 — 精确标识符候选加权（2026-07-30，保留代码，chunk 有 trade-off）**

- [x] **实现：** `KnowledgeIdentifierExtractor` + `KnowledgeIdentifierCandidateSupplement`（RRF 后 ILIKE 补召回 ±1 sibling）；`KnowledgeTitleEntityScoreBooster` 标识符加权；版本 `+identifier`
- [x] **单测：** `KnowledgeIdentifierExtractorTest`、`KnowledgeIdentifierCandidateSupplementTest` 等 **6 类**通过
- [x] **cross-dev-slice v2 `1f18c1ac`：** chunk R@8 **66.7%**（P1 `1f18c189` 75% ↓）、dual **100%** ✓、primary **2/12**（+1，dev-150+174）；dev-147 **candidate@50 ✗**（gold chunk_no=2 无 KI 编号）
- [x] **dev-fast-40 `1f18c1af`：** chunk **55%**（P1 60% ↓）、CROSS chunk **70%**（+10pp）、SINGLE **71.4%**（+7pp）、doc **100%**；VERSION chunk **0%**（P1 25% ↓）
- [x] **结论：** 标识符补召回对 dev-146 有效、对 dev-147 无效（gold 与编号 chunk 错位）；**保留** Phase 2 代码；下一步 Phase 3 子查询配额

**P4 — chunk 生成/索引优化（2026-07-30，re-publish + L3 已验证，当前语料 v5 无 neighbor）**

- [x] **导语独立召回：** 首个 `#` 标题前的无标题段标记 `section_heading=文档导语`（`KnowledgeChunker.PREAMBLE_SECTION_HEADING`），进入 lexical/精排
- [x] **YAML frontmatter 剥离：** 发布切片前移除 `---` 元数据块，避免挤占正文窗口
- [x] **neighbor embed 上下文：** `KnowledgeEmbedNeighborContext` 在 embed 文本追加相邻 chunk 各 80 字（不改 `content` 展示字段）
- [x] **单测：** `KnowledgeChunkerTest` 7/7、`KnowledgeChunkIndexTextTest` 2/2 通过
- [x] **re-publish 语料：** `scripts/republish-knowledge-corpus.ps1` **31/31** → `version_no=4`；manifest 405 chunks
- [x] **dev-240 gold 同步 v4：** seed `version_no` 3→4 + 重导入；checksum `2d1faeda…`
- [x] **L3 cross-dev-slice（`1f18c141`）：** chunk R@8 **75%**（P3 `1f18c030` 83.3% ↓）、dual **91.7%**（↓）、primaryRecallAt8 **0/12**（↓）；doc R@8 **100%** 持平
- [x] **L3 dev-fast-40（`1f18c150`）：** chunk R@8 **70%**（P3 `1f18c032` 62.5% ↑）、SINGLE **64.3%**、CROSS chunk **80%**、doc R@8 **97.5%**（dev-003 文档 miss）
- [x] **L3 dev-240 全量（`1f18c155`）：** chunk R@8 **57.5%**、CROSS chunk **60.4%**、dual **95.8%**、primaryRecallAt8 **39.6%**、candidateChunk@50 **92.5%**
- [x] **回归分析（cross-dev-slice）：** dev-146 P3/P4 均未命中；dev-147 gold 掉出 candidate@50（向量排序）；dev-154 覆盖选择挤出 signin-window（非 chunk_no 漂移）
- [x] **dev-240 全量 `1f18c1c9`：** chunk R@8 **52.9%**、primary **40.4%**、doc **96.7%**、CROSS dual **100%**、candidateChunk@50 **90.8%**、240/240 成功
- [x] **val-80 升级 v5：** seed 5 处 chunk 边界映射 + `sync-dev-gold-corpus-version.py`；checksum `b33f4438…`；重导入 80/80
- [x] **val-80 全量 `1f18c1d0`：** chunk **47.5%**、primary **32.5%**、CROSS dual **100%**（P2P3 `1f18c04c` 93.75% ↑）、CROSS chunk **62.5%**；门禁 vs v3 基线 **chunk/coverage 回归**（语料 v5 不可直接比）
- [x] **Phase 4A — 同语料可比消融（2026-07-30）：** `identifier`/`subquota` CLI 开关 + 门禁 `dataset_checksum_mismatch`；16 组 retrieval-only（4 变体 × 4 切片），输出 `output/rag-gold-runs/phase4a/`
- [x] **Phase 4B — identifier booster 校准（2026-07-30）：** `buildSignals()` 中 `eventIds` 提取与 `identifierSupplementEnabled` 联动；常量 BODY 0.15→0.08, TITLE 0.10→0.05；新增 3 条单测；cross-dev-slice p1/p2 消融现在代码层真正分离（无 chunk 回归）
- [x] **Phase 4B — 子查询本地 Top1 配额修正（2026-07-30，实验结论：退步）：** `KnowledgeSubQueryQuotaEnforcer.pickLocalTop` 直取各子查询 candidates.get(0) 作配额代表；单测 3/3 通过；cross-dev-slice chunk@8 0.750→0.667（dev-154 signin-window gold 因 local Top1≠gold 且改变 remaining pool 构成而 miss）；dev-fast-40 持平；代码已提交 `52e84a5`，**生产不变（下一步需精准 quota pick，非盲取 Top1）**
- [ ] **下一步：** dev-147 gold 边界讨论（gold chunk 未进 candidate@50 是 Candidate 层问题）；frozen-80 发布前再跑；探索 Cross-encoder reranker 是否能从 candidate@50 把 hard CROSS 题拉进 Top8；dev-154 signin-window gold 的精准 quota 策略（需确认 local rank）

**R2 精排边界（备忘）：**

- `RrfOnlyKnowledgeReranker`：当前 RRF 排序，作为默认与失败兜底；
- `CrossEncoderKnowledgeReranker`：批量精排 RRF Top30，超时/失败回退 RRF；
- 不使用聊天大模型逐条打分：成本高、延迟不稳、难复现；
- 精排输入必须含问题，以及候选的 `title / documentType / versionNo / effective window / sectionHeading / content`；
- 不改变 Workspace、Organization、PUBLISHED、effective window 等现有过滤边界；
- 记录 reranker 名称/版本、候选数、耗时和是否 fallback，不记录模型原始推理。

**启用生产精排的门槛：**

- Candidate Recall@30 已达到 R1 门槛；
- 相比 RRF，最终 Recall@8 提升 ≥5pp，或 MRR 提升 ≥0.08；
- 精排增加的 retrieval P95 ≤1.5 秒；
- val-80 改善且 frozen-80 不退化。

###### R3 — 精排后的软多样性与补检索护栏

1. 禁止固定“每文档最多 2 chunk”；改为软惩罚：
   - 同文档第 1 条不降权；
   - 第 2 条轻微降权；
   - 第 3 条及以后逐步降权；
   - 具体系数只通过 dev-fast-40 / dev-240 retrieval-only 比较确定。
2. 对 planner 命中多个类型、但候选只覆盖一个 document 的问题，允许触发现有的**唯一一次**全类型补检索。
3. 最终仍为 Top8；证据片段可由 300 增至 500 字，但必须验证生成 P95 增幅 <20%。

**R3 验收：**

- dev-240 最终 chunk Recall@8 ≥40%；
- CROSS_DOCUMENT 双文档命中率明显高于 RRF 基线；
- VERSION_CONFLICT Recall@8 ≥26%；
- OPERATION_PROCESS Recall@8 ≥25%；
- citationSupportRate ≥99%；
- 不以牺牲 SINGLE_DOCUMENT_FACT 明显退化换取跨文档提升。

###### R4 — 执行顺序与命令契约

```text
1. 先完成 Step M 全量 dev-240（baseline-metrics-v2）
2. 实现 R0：retrieval-only + 固定 slices + Candidate Recall
3. 实现 R1：标题/章节/版本词法召回 + Top40/40 候选 + query expansion
4. dev-fast-40 → dev-240 retrieval-only，确认候选上限
5. 实现 R2：RRF 与 Cross-encoder 同候选集离线对比
6. 实现 R3：软多样性 + 补检索护栏
7. dev-e2e-30 → 完整 dev-240 → val-80 → frozen-80
```

目标 CLI（实现后）：

```powershell
# 每轮参数调整：固定快速子集，不调用 LLM
.\scripts\run-rag-gold-evaluation.ps1 `
  -WorkspacePublicId "<ws>" -DatasetKey "ops-rag-v1" -DatasetVersion "dev-240" `
  -Mode "retrieval-only" -CaseKeysFile "evaluation/rag/gold/slices/dev-fast-40.txt"

# 候选方案通过后：全量检索，不调用 LLM
.\scripts\run-rag-gold-evaluation.ps1 `
  -WorkspacePublicId "<ws>" -DatasetKey "ops-rag-v1" -DatasetVersion "dev-240" `
  -Mode "retrieval-only"

# 精排方案定稿后：30 题端到端（可选开启精排）
.\scripts\run-rag-gold-evaluation.ps1 `
  -WorkspacePublicId "<ws>" -DatasetKey "ops-rag-v1" -DatasetVersion "dev-240" `
  -Mode "end-to-end" -CaseKeysFile "evaluation/rag/gold/slices/dev-e2e-30.txt" -Reranker on

# RRF vs Cross-encoder 离线对比（不调用 LLM）
.\scripts\run-rag-gold-evaluation.ps1 `
  -WorkspacePublicId "<ws>" -DatasetKey "ops-rag-v1" -DatasetVersion "dev-240" `
  -Mode "retrieval-only" -CaseKeysFile "evaluation/rag/gold/slices/dev-fast-40.txt" -Reranker on
```

**版本规则：** R0 仅增加评测能力，不改变线上检索版本；R1/R2/R3 线上行为变化统一从 `knowledge:rrf:v2` 起记录，具体候选生成与 reranker 配置必须进入 AgentRun / RAG run 元数据。`ChatPromptTemplate` 保持 `chat:v4`。

##### Step P — 拒答与跨文档 Prompt（`chat:v5`，2～3 天）

| 序号 | 任务 | 改动点 | 验收 |
|------|------|--------|------|
| P1 | 拒答话术固定 | `ChatPromptTemplate` v5：REFUSAL/无知识时「未知项」必须包含固定句式（如「当前知识库未覆盖该问题，无法给出运营口径」）；**禁止**要求复述「未检索到已发布企业知识」 | REFUSAL 5 题合规 **≥4/5** |
| P2 | 跨文档作答结构 | v5 护栏：CROSS 类问题「结论」须分文档列点，每点带 `[证据:…]`；版本冲突须写「以较新版本为准」 | CROSS_DOCUMENT 事实覆盖 **≥25%**（Step R 基线上） |
| P3 | 观测值判定对齐 | `RagGoldManualEvaluationRunner.observation`：`containsKnowledgeClaim` 改为「有结构化结论且非纯拒答模板」 | 与 P1 一致；ungrounded 率可略升但 REFUSAL 合规升 |
| P4 | 金标与回归 | 新增 3～5 条 REFUSAL/CROSS 回归用例进 seed（或 dev 子集）；`GoldEvaluationRunner` / RAG 均走 `chat:v5` | `./mvnw.cmd test` + dev-240 重跑；**frozen-80** 关键指标不退化 |
| P5 | 发布门禁 | 对比 Step M 基线：Recall@8、事实覆盖、REFUSAL 合规、forbiddenHitRate 均不退化；任一项退化则 `--baseline-run-id` 阻断 | PS 脚本 exit 2 可触发 |

**Step P 完成标志：** dev-240 REFUSAL ≥80%、CROSS 事实覆盖 ≥25%；跑 **frozen-80** 通过回归门禁后，将结论写入 `project-development-log.md`。

##### 推荐排期与命令

```text
Phase 1: Step M 全量 dev-240 → baseline-metrics-v2
Phase 2: R0 评测漏斗 → R1 候选生成 → dev-fast-40 / dev-240 retrieval-only
Phase 3: Candidate Recall 达标后实现 R2 精排 → R3 软多样性
Phase 4: dev-e2e-30 → 完整 dev-240 → val-80 → frozen-80
Phase 5: 检索版本通过门禁后再进入 Step P（chat:v5）
```

```powershell
# 全量基线（每步完成后）
.\scripts\run-rag-gold-evaluation.ps1 `
  -WorkspacePublicId "<ws>" -DatasetKey "ops-rag-v1" -DatasetVersion "dev-240"

# 失败题重跑（仅 Step M5 验证 carry-forward）
.\scripts\run-rag-gold-evaluation.ps1 ... -RetryFromRun "<run-id>"

# 回归门禁（Step P 后）
.\scripts\run-rag-gold-evaluation.ps1 ... -DatasetVersion "frozen-80" -BaselineRunId "<frozen-baseline>"
```

**暂不做：** 换 embedding 模型、多 Agent/多轮 ReAct 检索、独立向量数据库。Cross-encoder 已调整为 Step R 的候选方案，但只有 Candidate Recall 达标且离线精排满足质量/延迟门槛时才启用。


> **总览文档：** [`docs/superpowers/specs/2026-07-28-feedback-import-classification-evolution-design.md`](superpowers/specs/2026-07-28-feedback-import-classification-evolution-design.md)（导入 Canonical 格式、多数据源适配、Phase 0～E 演进路线）  
> **设计文档（v2）：** [`docs/superpowers/specs/2026-07-28-l2-expression-l1-topic-layer-design.md`](superpowers/specs/2026-07-28-l2-expression-l1-topic-layer-design.md)  
> **背景：** TapTap ~1200 条导入；~75% `unclassified` 淹没复核队列。方案：**L2 平台五类（跨游戏可比）→ L1 Workspace Topic Pack 钻取（按游戏 Skill 包）；零命中写 `topic_general` 而非 OTHER/复核；去掉「可行动主题覆盖率 KPI」**。

#### Wave 1 并行（2026-07-28）

- [x] **Import B+（C1）：** Canonical 表头 `feedback_text` 等精确自动映射，TapTap CSV 直进 Step 3；`import-auto-mapping.test.mjs`。
- [x] **Phase A（A1）：** 复核降噪 + `topic_general` + Data.vue Tab — 单测通过；**需重投影**后验收复核队列。
- [x] **RAG R1（B1）：** Markdown 标题切分 + embed 前缀 + overlap 100 — 8 项单测通过；**需重新发布**各版本文档。

#### Phase A：L1 复核降噪 + topic_general

- [x] **复核准入：** 仅 `ambiguous_topics` / `too_many_topics` / `mixed_sentiment` 进复核；`unclassified` 不再进候选。
- [x] **topic_general 兜底：** Pack 规则零命中写 `topic_general` link，进 L1 统计与钻取。
- [x] **复核页 Tab：** 按 reasonCode 分 Tab，不含 unclassified。

#### Phase B：平台 L2 + Pack 机制 + 粗→细看板

- [x] **`platform/expression-rules.toml` + `ExpressionClassifier`：** 五类 L2（suggestion/complaint/praise/neutral/other），每条必有主标签，零命中兜底 `expr_other`；`ExpressionClassifierTest` 8 项通过。
- [x] **Topic Pack 加载：** `config/analysis/packs/{pack_id}/` 经 `TopicPackRegistry` 启动期扫描加载；`TopicPackLoaderTest` + `TopicPackRegistryTest` 通过。
- [x] **Workspace Pack 绑定（V25）：** `workspace.topic_pack_id` 可空字段；`GET/PUT .../topic-pack`（OPERATOR+）+ `GET /api/v1/topic-packs`；Dashboard 页 Pack 切换 UI。
- [x] **L1 规则源切换：** 投影流水线改用 Workspace 绑定 Pack 的 `topic-rules.toml`（非全局 `issue-rules.toml`）；历史 link 不做 issue key→topic_* alias 映射，零命中仍写 `topic_general`。
- [x] **首包 `game-chaoziran:v1`：** topic-catalog + topic-rules 已加载并通过校验。
- [x] **Flyway `feedback_projection_annotation`（V23）+ 投影写入（`ProjectionAnnotationWriter`）+ L2 日聚合（`expression_metric_bucket`，源自标注行）。** 迁移 schema 测试 + Writer/BucketService 单测通过。
- [x] **Dashboard：** 首屏 L2 分布/趋势 + L2→L1 钻取 + 交叉样本 + **告警副屏（`alert_eligible` 子集展示）**；`Dashboard.vue` 路由 `/dashboard` + `dashboard-runtime-state.test.mjs`。
- [x] **API：** `expressions/{key}/topics` 与 `expressions/{key}/topics/{topicKey}/samples` 交叉样本；`DashboardServiceTest` + `DashboardControllerTest` 覆盖。

#### Phase C/D/E（可选，见 spec）

- [x] **Pack 级 LLM Topic Skill**（仅 general 子集，置信度门控；规则优先，Pack catalog 白名单，标注行冻结 prompt 版本）。
- [x] **Agent Tool / 报告** 支持 L2 与 L2×L1 查询（`EXPRESSION_DISTRIBUTION` / `EXPRESSION_TOPIC_DRILLDOWN` / `EXPRESSION_TOPIC_SAMPLES`；`EXPRESSION_INQUIRY` 意图；报告 `MergedData.expressionMentions` + `report:v2`）。
- [ ] **Genre Starter Pack 模板**；第二游戏 Pack 试绑验证多游戏扩展。

**验收标准（A+B）：** L2 非 other ≥ 85%；复核 < 100；Dashboard 可演示 L2 占比并钻取 L1；平台 core 无游戏专属议题硬编码；`topic_general` 为正常统计桶。**Phase B 后端 + Dashboard UI + Pack 切换已交付；数字指标与 Pack 规则生效仍依赖用户对真实 Workspace 手动重投影验证。**

- [x] 支持长评论最多关联两个既有主题，并按主题记录正面/负面/中性/混合情绪；主题过多、并列歧义、未分类或混合情绪进入按 Workspace 隔离的人工复核候选。人工只能确认、忽略或提交新主题候选，不会直接改写规则、历史链接或趋势指标。

**验收标准：** 每次改动 Prompt 后，可以比较哪些问题提升或退化，以及对应的成本和延迟变化。

## P2：证据化调查能力

- [x] 将数据查询封装为只读 Tool：趋势、主题分布、告警、样本反馈和时间范围比较。
- [x] 实现问题意图识别：趋势解释、异常调查、环比、版本前后比较和报告生成。
- [x] 实现单 Agent 的调查计划编排：根据意图选择最少必要 Tool，禁止为“看起来智能”引入多 Agent 自由协作。
- [x] 统一 Agent 输出结构：结论、证据、推测、未知项和建议动作。
- [x] 强制所有数值、时间和因果判断附带数据来源；证据不足时明确说明无法判断。
- [x] 使用 P1 评测集迭代 Prompt，不以主观感受作为唯一判断依据。

**验收标准：** 回答“为什么暴增”时，能够展示具体日期、数量、样本或告警依据，并清楚区分事实与推测。

## P3：企业知识库与 RAG（已完成）

- [x] 定义知识库文档生命周期：上传、审批、版本、失效和删除，并保持 Workspace 权限隔离。
- [x] 将获批文档切片并写入 PostgreSQL + pgvector；不在此阶段引入独立向量数据库。
- [x] 实现混合检索：元数据过滤、全文检索和向量召回。
- [x] 回答中展示文档名称、版本、片段和来源链接。
- [x] 增加 RAG 专项评测：召回率、引用正确率和无依据回答率。

**验收标准：** 询问版本公告、客服 SOP 或已知问题时，回答能够引用正确的知识文档片段。

### P3 已确认的轻量范围（2026-07-25）

- 文档输入仅支持 Markdown 与 TXT；不在首版引入 PDF、OCR 或外部独立向量数据库。
- 文档生命周期固定为“待审批 → 已发布 → 已失效 / 已删除”；新版本重新进入待审批，旧已发布版本自动失效。
- 先引入轻量 `Organization` 归属，不实现成员、角色、登录或细粒度权限；它们留待 P4 扩展。
- `Workspace` 定义为一个游戏、产品线或独立舆情分析对象，归属一个 Organization；它继续承载导入数据、主题、告警和分析上下文，不能重定义为对话窗口。
- `ChatSession` 才是类似豆包的独立对话窗口；同一 Workspace 下可创建多个会话，不需要为不同对话创建多个 Workspace。
- 知识文档归属 Organization，并使用可空 `target_workspace_id` 划分可见范围：为空时是组织通用文档；有值时仅服务当前游戏 Workspace。首版不支持一篇文档授权给多个指定游戏，跨游戏通用资料应作为组织通用文档维护。
- 首版文档类型固定为：版本公告、已知问题、客服 SOP / FAQ、舆情处置手册。
- Agentic RAG 采用受控单 Agent：先路由文档类型和过滤条件，再执行混合检索；证据不足时最多补检索一次。模型不能自由访问 SQL、仓储或无限循环调用 Tool。



## P4：业务闭环与协作能力（核心闭环已完成，外部协作集成待后续确认）

- [x] 实现异常调查工作流：固定只读 Tool 汇总趋势、告警历史和样本反馈，冻结为可复核证据；版本或活动事件源尚未接入时，明确标记不能推断因果。
- [x] 实现告警触发后的异步自动调查：生成待人工确认的调查卡片，不自动修改业务状态。
- [x] 实现提案式告警处置：系统仅生成确认、忽略、关闭三种固定提案；前端展示证据与影响预览，人工确认后由 Command Service 执行。
- [x] 为写操作补齐角色权限、幂等键、变更预览、操作审计和可撤销记录；不向模型暴露原始数据库写权限。
- [x] 支持人工纠错：以候选形式提交主题别名、规则候选或评测样例，不直接改写线上规则。
- [x] 将人工纠错接入评测闭环：Owner 仅在金标与 RAG 两套基线均未退化时发布候选。
- [ ] 生成每日简报、周报和版本复盘报告，且每项结论均附可追溯证据。
- [ ] 增加用户、角色、Workspace 成员和审计日志，支持企业协作。
- [ ] 后期评估外部协作集成：飞书、钉钉、Jira 等仅在内部处置工作流和审计稳定后接入；届时再确定通知策略、授权方式、失败重试与幂等边界。

**验收标准：** 一条告警可完成“发现、调查、确认、分派、处置、复盘”的完整闭环，所有关键操作可追溯。

## 当前建议的第一项

P4 核心业务闭环已完成。下一项应先补齐版本/活动事件数据源与报告口径，再评估长期会话摘要；飞书、钉钉、Jira 等外部协作集成仍需单独确认授权、通知策略、失败重试与幂等边界。

## P3 完成记录（2026-07-25）

- [x] 新增 `Organization → Workspace` 归属关系；P3 使用唯一默认组织承接现有 Workspace，用户、成员、角色和登录仍留在 P4。
- [x] 新增组织通用与 Workspace 专属两种文档范围；原文保存在 MinIO，元数据、不可覆盖版本、切片和向量保存在 PostgreSQL + pgvector。
- [x] 文档仅支持 Markdown/TXT；版本状态为 `PENDING_REVIEW → PUBLISHED → EXPIRED/DELETED`，发布前先校验状态，避免非法请求消耗对象存储与嵌入模型。
- [x] 实现 FTS + pgvector 的固定 RRF 混合检索，服务端强制组织/Workspace/已发布状态过滤；按问题类型规划首轮，证据不足时最多补检索一次。
- [x] 聊天链路将知识证据合并到受控调查证据，返回文档标题、版本、片段和应用内来源链接；AgentRun 仅保存检索轮次和证据快照，不保存原始思维链。
- [x] 新增 RAG 专项评测：题集由当前 Workspace 可见的已发布文档生成，每种类型最多一题并包含一题无知识依据问题；保存召回率、引用正确性、无依据回答率和脱敏逐题计数，不保存模型回答正文。
- [x] 评测页可运行并查看 RAG 评测历史；通用 Prompt 金标评测与 RAG 专项评测使用独立历史表，避免指标 JSON 口径混用。



## P2 完成记录（2026-07-25）

- [x] 受控只读 Tool：趋势、主题分布、告警、脱敏样本、固定时间范围比较，以及版本数据可用性说明。
- [x] 规则化意图识别与最小 Tool 计划；未引入多 Agent 或模型自由选库查询。
- [x] 聊天输出采用“结论、证据、推测、未知项、建议动作”结构，并要求数值、时间和异常指标引用证据索引。
- [x] AgentRun 审计保存计划与证据快照；前端可在本次回答下查看受控证据。
- [x] P1 金标评测复用 `chat:v2` Prompt，并新增证据引用率（格式合规代理指标）。



### 待办：组织级 Workspace 与成员授权管理

- [ ] 将 Workspace 创建入口从全局侧边栏迁移到仅 Owner 可见的组织/工作区管理页；普通成员无授权 Workspace 时仅展示“请联系管理员”的空状态。
- [ ] 补齐 Owner 的成员管理界面：创建成员、设置组织角色，并为既有成员增删多个 Workspace 的访问范围。
- [ ] 保持后端为唯一授权边界：前端不保存角色，不以界面隐藏代替 `WorkspaceAccessService` 的 Owner 与 `workspace_member` 校验。
- [ ] 为 Owner、已授权成员、无授权成员分别补充 Workspace 列表与命令拒绝的回归测试；验证跨 Workspace 数据始终隔离。