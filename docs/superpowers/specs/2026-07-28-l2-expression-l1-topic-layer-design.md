# L2 平台表达层 + Workspace Topic Pack（L1 细分）设计

> 状态：待用户复核（v3，数据模型已确认）  
> 日期：2026-07-28  
> 分支：`feature/data-cell-rule-issue-merging`（延续当前分支）  
> 关联总览：[`2026-07-28-feedback-import-classification-evolution-design.md`](2026-07-28-feedback-import-classification-evolution-design.md)（导入 + 分类 + 分阶段演进）  
> 前置文档：`docs/superpowers/specs/2026-07-27-multi-aspect-feedback-review-design.md`、`docs/knowledge-sources/游戏舆情分析与风险分级手册.md`  
> 背景：TapTap 真实导入约 1200+ 条评论；仅 ~25% 命中现有 8 类全局 `issue-rules.toml`；~75% 因 `unclassified` 进入人工复核。产品目标调整为 **L2 粗分（跨游戏可比）→ L1 细分（按游戏 Skill Pack 钻取）**，而非「可行动主题覆盖率 KPI」。

## 1. 目标与产品定位

### 1.1 主目标（P0）

建立 **粗→细** 的双层分类叙事，服务舆情情绪/建议分布：

- **L2（平台层·粗分）：** 建议 / 吐槽 / 好评 / 体验分享 / 其他——**全 Workspace、全游戏口径一致，可跨游戏对比**；
- **L1（Workspace Topic Pack·细分）：** 每个游戏/Workspace 挂载自己的议题词表与规则——**仅在当前 Workspace 内钻取，不跨游戏硬比**；
- 看板首屏展示 **L2 结构与趋势**；点选某一 L2 类后，展示 **该 Workspace Pack 下的 L1 议题分布**；
- 每条评论 **必有 L2**（至少 `expr_other`）；L1 零命中时写入 Pack 内置的 **`topic_general`（综合/未指向具体议题）**，视为正常细类，**不是失败指标**。

### 1.2 副目标（P1，保留）

- EWMA 告警、主题趋势、调查 Tool、主题级情绪（V21/V22）、知识库 RAG **不删除**；
- **告警**仅作用于 Pack 内标记 `alert_eligible=true` 的 L1 议题，独立副屏展示，不占据舆情首屏；
- 人工复核只承接 L1 **歧义 / 多议题 / 议题级 mixed**；`topic_general` 与 L2 `expr_other` **均不进复核**。

### 1.3 非目标

- **不在平台 core 写死**某一游戏的细议题（如 TapTap 专属的「匹配/策划/画面」全局列表）；
- 不在 MVP 做 Skill 注册中心、热加载市场或多 Pack 并行 A/B；
- L2 不走 LLM 默认路径（规则优先）；LLM 仅作为 **Pack 可选补位 Skill**（Phase C）；
- 不修改已应用 Flyway；新事实通过新迁移 + Pack/规则升版 + 重投影写入。

---

## 2. 概念模型：平台 L2 × Workspace Pack L1

| 层 | 回答的问题 | 作用域 | 粒度 | 典型输出 |
|----|------------|--------|------|----------|
| **L2 表达/意图（粗）** | 这条评论**整体在表达什么** | **平台**，跨游戏可比 | 每条 1 主标签 | `expr_suggestion` |
| **L1 议题对象（细）** | **在聊哪个方面/议题** | **Workspace Pack**，游戏自定义 | 每条 0～2 + `topic_general` 兜底 | `topic_matchmaking` |
| **主题级情绪** | 对**某个 L1 议题**的态度 | Workspace | 每 feedback×topic | `positive` / `mixed` |

**产品交互层级（粗→细）：**

```text
Dashboard 首屏：L2 五类占比与趋势（跨游戏可比）
    ↓ 点击「建议类」
二级：当前 Workspace Pack 的 L1 分布（如 匹配 32%、玩法 28%、综合 28%…）
    ↓ 点击「匹配/组队」
三级：L2×L1 交叉样本 + 可选议题级情绪
```

**示例（同一 Workspace Pack）**

| 评论摘要 | L2（粗） | L1（细） |
|----------|----------|----------|
| 「希望优化匹配，太慢了」 | `expr_suggestion` | `topic_matchmaking` |
| 「策划有问题，退游了」 | `expr_complaint` | `topic_gameplay` |
| 「画面真香，安利！」 | `expr_praise` | `topic_visual` |
| 「玩了 50 小时，总体来说还行」 | `expr_neutral` | `topic_general` |
| 「登录不上，气死了」 | `expr_complaint` | `topic_network` |

**跨游戏对比规则：**

- **可以比：** L2（「A 游戏建议占比 vs B 游戏建议占比」）；
- **不可直接比：** L1 细类 key（除非两 Workspace 显式共用同一 Pack 版本）；
- **组织级汇总：** 默认只聚合 L2；L1 仅在选定 Workspace 后展示。

### 2.1 术语表（数据与文档用语）

| 术语 | 表/实体 | 含义 |
|------|---------|------|
| **反馈事件** | `feedback_event` | 导入后的脱敏评论源数据；与分类版本无关 |
| **投影标注 / 标注行** | `feedback_projection_annotation` | 每条评论在**一次投影**下的 **L2 快照**（1 行）；含表达类与 Pack 版本 |
| **议题关联 / link** | `feedback_issue_link` | 每条评论在**一次投影**下与 **L1 议题**的关联（0～2 行）；**文档中的 link 仅指此表** |
| **L2** | 存在标注行字段中 | 粗分：建议/吐槽/好评等；**不是** link |
| **L1** | 存在 link 行中 | 细分：Pack 内议题；零命中写 `topic_general` link |

**为何分两张分类事实表：** L2 每条评论恰好 1 个；L1 每条 0～2 个且带议题级情绪。L2 不进 link 表，避免与多行议题关联混淆。

---

## 3. L2 平台表达层（全 Workspace 共用）

### 3.1 Taxonomy（v1 定稿）

| canonical_key | 中文名 | 定义 |
|---------------|--------|------|
| `expr_suggestion` | 建议/诉求 | 希望改、能不能、应该、优化、加点等 |
| `expr_complaint` | 吐槽/不满 | 差、坑、劝退、退游、失望、垃圾等 |
| `expr_praise` | 好评/推荐 | 好玩、推荐、安利、良心、神作等 |
| `expr_neutral` | 体验分享 | 客观叙述、玩了 X 小时；短咨询（怎么/请问）暂并入此类 |
| `expr_other` | 其他 | 过短、纯表情、规则无法判断 |

配置文件：`config/analysis/platform/expression-rules.toml`，版本 `platform:expression:v1`。

### 3.2 字段

| 字段 | 说明 |
|------|------|
| `primary_expression` | L2 枚举，必填 |
| `expression_confidence` | 0～1 |
| `expression_method` | v1 固定 `rule` |
| `mixed_expression` | 多意图同现且分差低于阈值 |

### 3.3 规则要点

- 与 `RuleFirstIssueClassifier` 同口径打分；同分时：`suggestion > complaint > praise > neutral > other`；
- L2 **不进人工复核**；不确定 → `expr_other` + 统计。

---

## 4. L1 Workspace Topic Pack（Skill 包）

### 4.1 什么是 Topic Pack

**Topic Pack** = 绑定到 Workspace 的 **L1 分类 Skill 包**（MVP 以**规则 toml + 目录元数据**实现，不引入独立 Skill 运行时市场）：

```text
config/analysis/packs/
  platform/
    expression-rules.toml          # L2，全平台
  game-chaoziran/
    pack.toml                      # pack_id, version, display_name
    topic-catalog.toml             # 议题列表、中文名、排序、alert_eligible
    topic-rules.toml               # L1 规则（结构同 issue-rules.toml）
    topic-normalize.toml           # 可选，游戏专属同义词
  _templates/
    genre-moba/                    # Phase 2+：Genre Starter Pack
    genre-gacha/
```

**Workspace 绑定（配置示例）：**

```yaml
insightflow:
  analysis:
    expression_rules: platform:expression:v1
    topic_pack_id: game-chaoziran
    topic_pack_version: game-chaoziran:v1
    topic_llm_skill_enabled: false   # Phase C 可选
```

每个 Workspace **恰好绑定一个 Pack 版本**；换游戏 = 换 Pack 绑定 + 重投影，**不改平台 L2**。

### 4.2 Pack 内 L1 约束（平台只约束形态，不约束词表）

| 平台约束 | 说明 |
|----------|------|
| 每条 0～2 个 L1 议题 | 与 V21/V22 多主题上限一致 |
| **必须**含 `topic_general` | 规则零命中时写入，进趋势与钻取，不进复核 |
| 每个 L1 可标 `alert_eligible` | 仅此类议题参与 EWMA 告警 |
| Pack 有独立 `version` | 升版触发该 Workspace 重投影 |
| 按 `workspace_id` 隔离 | 禁止跨 Workspace 读取 Pack 事实 |

**Pack 内自由：** 议题数量（建议 8～20）、canonical_key 命名、关键词、是否启用 LLM 补标 Skill。

### 4.3 默认 Pack 示例（当前 TapTap 游戏，非平台硬编码）

`game-chaoziran:v1` 示例议题（**仅作首包参考，不写入 Java 常量**）：

| canonical_key | 中文名 | alert_eligible |
|---------------|--------|----------------|
| `topic_matchmaking` | 匹配/组队 | false |
| `topic_gameplay` | 玩法/平衡 | false |
| `topic_visual` | 画面/性能 | false |
| `topic_stability` | 稳定性/bug | **true** |
| `topic_network` | 网络/登录 | **true** |
| `topic_payment` | 付费/经济 | **true** |
| `topic_social` | 社交/举报/外挂 | **true** |
| `topic_content` | 内容/版本/活动 | false |
| `topic_service` | 客服/封号/申诉 | **true** |
| `topic_general` | 综合/未指向 | false |

**旧 `issue-rules.toml` 8 类映射：** 在首包迁移表中做 alias（如 `login_failure` → `topic_network`，`bug_gameplay` → `topic_stability`），避免历史 link 断裂。

### 4.4 L1 分类出口

| 出口 | 条件 | 行为 | 复核 |
|------|------|------|------|
| **AUTO** | 规则高置信，≤2 议题，非 ambiguous | 写 **议题 link** + 议题级情绪 | 否 |
| **REVIEW** | ambiguous / too_many / 议题级 mixed | 写 link（若有）+ 候选 | 是 |
| **GENERAL** | 零规则命中 | 写 **`topic_general` 议题 link** | **否** |

**相对现状的关键变更：**

- 删除「unclassified 不写 link」→ 改为 **`topic_general` 写 link**；
- `unclassified` **不再**进 `FeedbackReviewCandidate`。

### 4.5 可选 LLM Topic Skill（Phase C，Pack 级开关）

- **不**用于 L2；**不**用于导入全量主路径；
- 仅对 `topic_general` 或低置信子集，在 Pack 内调用受控 Prompt（可放在 `pack.llm-prompt.md`）；
- 输出议题候选 + 置信度；低置信仍落 `topic_general`；
- **不得**自动改写 Pack 规则或历史 link；规则变更须人工确认 + Pack 升版。

### 4.6 Genre Starter Pack（Phase 2+，非 MVP）

- `_templates/genre-moba` 等提供冷启动词表；
- 新 Workspace 选模板 → 复制为 `game-xxx` Pack → 运营微调 → 绑定；
- 不做 Pack 在线编辑器，首版文件系统 + 配置即可。

---

## 5. 投影流水线

```text
FeedbackEvent（已脱敏）
  ├─ IssueTextNormalizer（平台 + Pack 可选 normalize）
  ├─ ExpressionClassifier（平台 L2）
  ├─ TopicPackLoader（按 Workspace 加载 Pack）
  ├─ PackTopicClassifier（Pack L1，结构同 RuleFirstIssueClassifier）
  │     └─ 零命中 → [topic_general]
  ├─ TopicSentimentAnalyzer（议题级情绪）
  ├─ reviewReason() → 仅 ambiguous / too_many / mixed → REVIEW
  └─ ProjectionFactWriter
       ├─ feedback_projection_annotation（每 event 1 行：L2 + Pack 版本）
       ├─ feedback_issue_link（每 event 0～2 行：L1 议题关联，含 topic_general）
       ├─ expression_metric_bucket（L2 日聚合，源自标注行）
       ├─ issue_metric_bucket（L1 日聚合，源自 link，口径不变）
       └─ FeedbackReviewCandidate（仅 L1 REVIEW）
```

L2 与 L1 **并行计算**；同一投影事务写入；Pack / 表达规则版本写入标注行便于追溯。

---

## 6. 数据模型

分类事实采用 **「1 行投影标注（L2）+ 0～2 行议题 link（L1）」**；不新增 `feedback_expression` 表。

### 6.1 新增 `feedback_projection_annotation`（L2 快照）

每条评论在每次成功投影中 **恰好 1 行**，存 L2 与本次投影使用的 Pack 快照。

| 列 | 说明 |
|----|------|
| `workspace_id` | 租户隔离 |
| `workspace_projection_id` | 所属投影 |
| `feedback_event_id` | 关联脱敏评论 |
| `primary_expression` | L2 枚举 |
| `expression_confidence` | 0～1 |
| `expression_method` | v1 固定 `rule` |
| `mixed_expression` | boolean |
| `expression_rule_version` | 如 `platform:expression:v1` |
| `topic_pack_id` | 本次投影绑定的 Pack id |
| `topic_pack_version` | 如 `game-chaoziran:v1` |

唯一约束：`(workspace_projection_id, feedback_event_id)`。

**与 `feedback_event` 的分工：** event 是导入源；annotation 是**可版本化、可重算**的分类快照。重投影时删除/覆盖该 projection 下旧标注行，与 link 表同策略。

### 6.2 保留 `feedback_issue_link`（L1 议题关联）

- **不改表名**；语义上称 **议题 link**，一行 = 评论 × 一个 L1 议题；
- 每 event 0～2 行（GENERAL 出口为 1 行 `topic_general`）；
- 继续承载 `assignment_method`、`confidence`、**议题级 `sentiment`**；
- 告警与 `issue_metric_bucket` **只读 link**，不读标注行。

Pack 版本 **以标注行为准**追溯；link 行无需重复存 pack 字段（可选冗余，MVP 不冗余）。

### 6.3 聚合桶

| 表 | 数据源 | 维度 | 用途 |
|----|--------|------|------|
| `expression_metric_bucket` | `feedback_projection_annotation` | date × L2 | 首屏趋势 |
| `issue_metric_bucket` | `feedback_issue_link` | date × L1 | 二级钻取、告警 |
| L2×L1 交叉 | 标注 ⋈ link（同 projection + event） | date × L2 × L1 | MVP 可 API 实时 JOIN，不必单独建表 |

### 6.4 Dashboard 指标（修订：去掉「L1 覆盖率 KPI」）

**首屏展示：**

| 指标 | 说明 |
|------|------|
| `totalEvents` | 评论总数 |
| `expressionDistribution` | L2 五类占比 |
| `expressionTrend` | L2 趋势序列 |
| `reviewPendingCount` | L1 待复核（次要 KPI） |

**不展示：**「已纳入可行动主题 X%」「l1Linked / l1Other」。

**二级（需 `expressionKey` 参数）：**

| 指标 | 说明 |
|------|------|
| `topicDistributionInExpression` | 某 L2 下 L1 占比（含 `topic_general`） |
| `topicPackId` / `topicPackVersion` | 当前 Workspace 使用的 Pack |

---

## 7. API 与前端

### 7.1 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `.../dashboard` | L2 分布 + 趋势 + reviewPending + pack 信息 |
| GET | `.../expressions/{exprKey}/topics` | L2 下 L1 分布与趋势 |
| GET | `.../expressions/{exprKey}/topics/{topicKey}/samples` | 交叉样本 |
| GET | `.../topics` | 现有议题列表（Pack catalog） |
| GET | `.../feedback-reviews` | 复核（无 unclassified Tab） |

### 7.2 Dashboard 线框

```text
┌─ 本周评论结构（L2）────────────────────────────┐
│  [建议 38%] [吐槽 25%] [好评 20%] …             │
│  7 天趋势折线（L2 可切换）                       │
└────────────────────────────────────────────────┘
        ↓ 点击「建议」
┌─ 建议类 · 议题分布（本游戏 Pack）───────────────┐
│  匹配/组队 32% │ 玩法/平衡 28% │ 综合 28% …      │
│  [样本] [趋势]                                   │
└────────────────────────────────────────────────┘
┌─ 可行动议题告警（alert_eligible 子集）──────────┐  ← 副屏/折叠
│  稳定性 ↑  网络 →  …                            │
└────────────────────────────────────────────────┘
```

### 7.3 Data.vue

- 复核区：**L1 议题歧义待确认**；
- Tab：`ambiguous_topics` | `too_many_topics` | `mixed_sentiment`；
- 左侧议题列表来自 **当前 Pack catalog**，不再暗示「未分类 = 待办」。

### 7.4 Agent / 报告（Phase D）

- Tool：`queryExpressionDistribution`、`queryTopicDistribution(expressionKey)`；
- 跨 Workspace 问题只返回 L2；L1 需明确 Workspace 上下文。

---

## 8. 分阶段实施

| 阶段 | 内容 | 验收 |
|------|------|------|
| **Phase A** | L1 复核降噪；零命中改 `topic_general`；复核 Tab | 复核 < 100；general 有统计 |
| **Phase B** | 平台 L2 + Pack 加载器 + 首包 + `feedback_projection_annotation` + L2 看板 + L2→L1 钻取 | L2 非 other ≥ 85%；钻取可用 |
| **Phase C**（可选） | Pack 级 LLM Topic Skill | general 占比下降 |
| **Phase D**（可选） | Agent Tool、报告、Genre 模板 | 多游戏接入第二个 Pack 验证 |
| **Phase E**（可选） | 告警仅 alert_eligible；旧 issue key 迁移完成 | 告警不误伤 general |

**推荐 MVP：Phase A + B。**

---

## 9. 验收标准（Phase A + B）

1. **L2：** `expr_other` ≤ 15%；Dashboard 首屏为 L2 分布与趋势。  
2. **L1 粗→细：** 点击 L2 可看到 Pack 内 L1 分布；`topic_general` 为正常桶。  
3. **复核：** 无 unclassified 候选；队列仅 ambiguous/too_many/mixed。  
4. **多游戏就绪：** 平台 core 无 TapTap 专属议题常量；第二个 Pack 仅增目录+配置即可试绑（不必首期真的接第二游戏）。  
5. **告警兼容：** 现有 EWMA 对 link 仍有效；general 默认 `alert_eligible=false`。  
6. **隔离：** 跨 Workspace 不可互读 Pack 事实与复核。  
7. **测试与构建：** 相关单测 + `mvnw.cmd test` + 前端 test/build 通过。

---

## 10. 风险与待定项

| 项 | 默认决策 |
|----|----------|
| Pack 抽象是否过度 | MVP 仅文件目录 + Workspace 配置字段，不做 Skill 市场 |
| 首包词表不准 | 允许 1～2 轮迭代 `game-chaoziran:v2`，用 general 率监控 |
| general 占比过高 | 可接受；Phase C 再补 LLM，不以覆盖率为羞耻 KPI |
| 与 2026-07-27 spec | unclassified 进复核 → **废止**；以本文为准 |
| 旧 issue key | 首包 alias 映射 + 文档记录 |

---

## 11. 与现有文档的关系

- **延续：** V21/V22 多主题上限、议题级情绪、复核状态机。  
- **修订：** L1 从「全局可行动 8 类」→ **Workspace Topic Pack**；未命中 → `topic_general`。  
- **todo：** `docs/agent-optimization-todo.md` 与本 spec 对齐。

---

## 12. 确认记录

- [x] **数据模型：** L2 用 `feedback_projection_annotation`（1 行/事件/投影）；L1 保留 `feedback_issue_link`（0～2 行）；不单独建 `feedback_expression`。
- [ ] L2 五类定稿（inquiry 并入 neutral）  
- [ ] 首包 `game-chaoziran:v1` 议题表是否采用 §4.3 示例  
- [ ] Dashboard 首屏仅 L2，L1 钻取在二级  
- [ ] MVP 范围 Phase A+B，LLM Topic Skill 后置 Phase C  

上述 unchecked 项确认后从 Phase A 开工。
