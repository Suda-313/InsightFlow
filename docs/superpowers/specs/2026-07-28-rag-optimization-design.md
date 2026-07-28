# 企业知识库 RAG 优化设计

> 状态：待实施  
> 日期：2026-07-28  
> 分支：延续当前主线  
> 关联文档：  
> - [`2026-07-26-knowledge-base-real-flow-design.md`](2026-07-26-knowledge-base-real-flow-design.md)（P3 知识库基线）  
> - [`2026-07-25-agentic-rag-p3-design.md`](2026-07-25-agentic-rag-p3-design.md)（Agentic RAG 边界）  
> - [`docs/agent-optimization-todo.md`](../../agent-optimization-todo.md)（RAG 评测与优化待办）

## 1. 目的与规模假设

本文定义 InsightFlow **企业知识库 RAG** 的渐进优化路线，在 **不过度设计** 的前提下，支撑语料从当前少量文档扩展到 **50～100 篇** Markdown/TXT（预估 **200～1000 个 chunk**）。

**产品边界（不变）：**

- RAG 服务 **版本公告、已知问题、客服 SOP、舆情手册** 的问答与引用；  
- **不**替代数据 Tool（趋势、告警、评论分布）；  
- **不**把玩家 TapTap 评论向量化进知识库（评论走投影 + L2/L1）；  
- 保持 **PostgreSQL + pgvector** 模块化单体，不引入独立向量数据库。

---

## 2. 现状基线（已实现）

### 2.1 切分（`KnowledgeChunker`）

| 项 | 现状 |
|----|------|
| 策略 | 空行分段 → 短段合并 → 超长段按 **字符窗口** 硬切 |
| 窗口 | 默认 **1000 字符**（`insightflow.knowledge.chunk-max-characters`） |
| overlap | 无 |
| 标题感知 | 无（不识别 `# / ##`） |
| 时机 | **发布（PUBLISHED）时** 切分并嵌入；待审核不进 RAG |
| token_count | 字符长度近似，非真实 tokenizer |

### 2.2 存储

| 层级 | 位置 |
|------|------|
| 原文 | MinIO（`knowledge_document_version.object_key`） |
| 元数据 | `knowledge_document`、`knowledge_document_version` |
| 切片+向量 | `knowledge_chunk`：`content`、`content_tsv`（FTS）、`embedding vector(1024)` |
| 索引 | GIN(fts) + ivfflat(cosine, lists=100) |

### 2.3 Embedding

| 项 | 值 |
|----|-----|
| 模型 | **`text-embedding-v3`**（DashScope OpenAI-compatible） |
| 维度 | **1024**（与 V13 迁移绑定，改模型需新迁移 + 全量 re-embed） |
| 配置 | `KNOWLEDGE_EMBEDDING_MODEL` / `KNOWLEDGE_EMBEDDING_DIMENSIONS` |

### 2.4 检索（`KnowledgeSearchTool` + `JdbcKnowledgeVectorStore`）

1. 问题 embed；  
2. `KnowledgeRetrievalPlanner` 按关键词收窄文档类型（四类）；  
3. FTS Top32 + pgvector Top32 → **固定 RRF** 融合；  
4. 组织 + Workspace 可见 + 仅 **PUBLISHED**；  
5. 首轮有类型收窄且证据不足 → **最多补检索 1 次**（全类型）；  
6. 返回最多 **8** 条证据（片段截 300 字）。

### 2.5 已知局限

- 中文 FTS 使用 `simple` 配置，exact 关键词弱，主要靠向量；  
- 多文档时 Top8 可能集中在同一篇；  
- 切片无文档/章节上下文，易 **引错文档**；  
- 评测集小（~5 题），语料扩后需同步扩充。

---

## 3. 优化目标（100 篇语料下）

| 目标 | 说明 |
|------|------|
| **引对文档、引对章节** | 版本/已知问题/SOP 类问题指向正确 doc + section |
| **控制噪声** | 50～100 篇时 Top8 不堆同一文档 |
| **可回归** | 评测集随语料扩至 20～30 题，改 Chunker/检索可对比 |
| **延迟可控** | 检索 P95 &lt; 3s（不含生成） |
| **架构稳定** | 不换向量库、不上 Cross-encoder（除非评测证明不够） |

---

## 4. 明确不做（即使 100 篇）

- 独立 Milvus/Qdrant 等向量数据库  
- LLM 自动决定切分边界（上传/发布链路）  
- 每 Workspace 不同 embedding 模型  
- 玩家评论 / 舆情反馈进向量库  
- HyDE、多轮 ReAct 检索、Cross-encoder 重排（**默认不做**，见 §8 Phase 3）  
- PDF/OCR（无明确需求不做）

---

## 5. 分阶段优化路线

### 规模分档

```text
阶段 1   语料 <30 篇     切分 + 前缀 + 评测 15 题
阶段 2   30～60 篇       文档限流 + 中文 FTS + 补检索收紧 + 评测 20 题
阶段 3   60～100 篇      索引 REINDEX SOP + 窗口评测定稿 + 评测 30 题
```

---

### Phase R1 — 切分与嵌入上下文（优先，语料 &lt;30 篇即可做）

**目标：** 提升「召回到对片段 / 引用的文档章节正确性」，不改动检索 SQL 架构。

| 任务 | 说明 | 验收 |
|------|------|------|
| **R1.1 Markdown 标题切分** | 先按 `#～###` 分 section；section 超长再字符窗口切；不跨 section 硬切 | Chunker 单测 + 样例文档人工 spot check |
| **R1.2 Embed 上下文前缀（必选）** | 嵌入时在正文前拼：`[标题 \| TYPE \| vN]\n## 章节\n正文`；展示/API 仍可只显示正文或带标题摘要 | 跨文档混淆评测题引用正确率上升 |
| **R1.3 评测扩至 15 题** | 含真实问法、陷阱题、至少 2 道跨文档混淆 | 题集 `dataset_version` 升版；基线可复跑 |
| **R1.4 已发布文档重建** | 改 Chunker/前缀后各版本 **重新发布** 或提供一次性 re-embed 任务 | chunk 数与前缀格式可查询 |

**可选（R1 末）：**

- **R1.5 overlap ~100 字**：仅窗口硬切时启用，约 10% 重叠。

**配置：** 仍用 `chunk-max-characters`；改窗口需跑评测对比，不增加运行时多套策略。

---

### Phase R2 — 检索与证据（30～60 篇）

**目标：** 降低多文档噪声，改善中文关键词命中。

| 任务 | 说明 | 验收 |
|------|------|------|
| **R2.1 按文档限流** | 最终 Top8 中每个 `document_id` 最多 **2** 个 chunk（SQL 或 Java 后处理） | 问「登录问题」时证据来自 ≥2 类文档（如 KNOWN_ISSUE + SOP） |
| **R2.2 中文 FTS 补强** | 择一：`pg_bigm` / 额外 keywords 列（标题+章节+类型中文名）GIN；不改用户 SQL 拼接方式 | 「登录失败」类 exact 词召回提升（评测或金标） |
| **R2.3 收紧补检索** | 首轮已有足够证据（如 ≥2 chunk 且 Top1 分数达标）则 **不** 做第二次全类型检索 | 平均检索 round 下降；噪声 case 减少 |
| **R2.4 证据卡片** | 展示片段 300→**500** 字；固定带 `标题 · vN · chunk_no` | Agent 引用可人工核对 |
| **R2.5 评测扩至 20+ 题** | 每文档类型 ≥5 题；过期版本 / 不可见 Workspace 边界题 | 与 R1 指标同口径对比 |

---

### Phase R3 — 规模运维与微调（60～100 篇）

**目标：** 索引与参数适配 ~1000 chunk，仍不换架构。

| 任务 | 说明 | 验收 |
|------|------|------|
| **R3.1 ivfflat 调参 + REINDEX SOP** | 全量 re-publish 后 `REINDEX`；`lists` 按 chunk 数调整（经验 `≈ sqrt(n)`，500 行试 32～64） | 发布/runbook 文档化 |
| **R3.2 窗口定稿** | 用评测对比 800 / 1000 / 1200 字符，**选一档**写死 | 记录于 spec + 配置默认值 |
| **R3.3 评测 30 题** | 覆盖跨文档、跨版本、拒答、口语问法 | 作为 100 篇语料门禁 |
| **R3.4 失效版本 chunk 清理（可选）** | `EXPIRED` 版本 chunk 定期删或软删，减表膨胀 | 仅当 EXPIRED 行数影响 REINDEX 时做 |

---

### Phase R4 — 仅在评测仍不达标时（默认不做）

满足 **全部** 条件再评估其中一项：

- Phase R1～R3 完成；  
- 30 题评测上引用正确率仍低于产品可接受线；  
- 已排除 Prompt 与题集问题。

| 选项 | 说明 |
|------|------|
| 查询改写（单次 LLM 扩写） | 仅向量召回差且 FTS 已补强 |
| 换 embedding 模型 | 需 Flyway 新维度 + 全量 re-embed + 新基线 |
| Cross-encoder 重排 | chunk &gt;3000 且 Top8 常错 |

---

## 6. 与 RAG 评测待办的关系

[`docs/agent-optimization-todo.md`](../../agent-optimization-todo.md) 中 **「RAG 评测贴近真实业务」** 侧重 **题集、指标口径、对外表述**；本文侧重 **Chunker、检索、索引、规模运维**。

**分工：**

| 文档 | 负责 |
|------|------|
| 评测待办 | 语料规模、真实问法、陷阱/混淆题、指标拆分、演示口径 |
| 本文 | 切分、前缀、限流、FTS、REINDEX、阶段验收 |

实施时 **R1 与评测扩充应并行**（无评测则无法证明优化有效）。

---

## 7. 成功标准（汇总）

### Phase R1 完成

- [ ] Markdown 标题切分上线；已发布文档已重建 chunk  
- [ ] Embed 前缀含标题、类型、版本号  
- [ ] 评测 ≥15 题，`dataset_version` 记录  
- [ ] 召回率 / 引用正确率不低于改前，或一项升 ≥10% 另一项不降  

### Phase R2 完成（30～60 篇语料）

- [ ] Top8 文档限流生效  
- [ ] 中文 FTS 或 keywords 补强上线  
- [ ] 评测 ≥20 题，含跨文档混淆  
- [ ] 检索 P95 &lt; 3s（不含生成）  

### Phase R3 完成（60～100 篇语料）

- [ ] REINDEX / lists 调参 SOP 已文档化并执行过一次  
- [ ] chunk 窗口评测定稿  
- [ ] 评测 ≥30 题作为回归门禁  

---

## 8. 实施注意事项

1. **改 embed 文本或维度 → 必须 re-embed**（重新发布各版本或批处理任务）。  
2. **不改** `vector(1024)` 列除非新 Flyway 迁移。  
3. **Agent 边界不变**：数值/趋势仍走调查 Tool；KB 只答「文档怎么说」。  
4. **简历/演示口径**：仍写「规则代理指标 + 标注题集版本」，不写「RAG 100% 准确」。

---

## 9. 确认记录

- [x] 目标语料规模：50～100 篇，不换向量库架构  
- [x] R1 必选：标题切分 + embed 前缀  
- [x] R2 必选（达 30 篇前完成）：文档限流 + 中文 FTS  
- [ ] Phase R1 开工授权  
