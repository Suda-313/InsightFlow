# 反馈导入与分类体系演进设计（总览）

> 状态：待用户复核  
> 日期：2026-07-28  
> 分支：`feature/data-cell-rule-issue-merging`（延续当前分支）  
> 关联文档：  
> - [`2026-07-28-rag-optimization-design.md`](2026-07-28-rag-optimization-design.md)（RAG 切分/检索优化，50～100 篇语料）  
> - [`2026-07-28-l2-expression-l1-topic-layer-design.md`](2026-07-28-l2-expression-l1-topic-layer-design.md)（分类细节与表结构）  
> - [`2026-07-26-taptap-review-export-design.md`](2026-07-26-taptap-review-export-design.md)（TapTap 导出脚本）  
> - [`2026-07-19-csv-import-reliability-design.md`](2026-07-19-csv-import-reliability-design.md)（导入可靠性）  
> - [`2026-07-27-multi-aspect-feedback-review-design.md`](2026-07-27-multi-aspect-feedback-review-design.md)（多主题与复核，部分条款被本文修订）

## 1. 文档目的

本文汇总 InsightFlow 在 **TapTap 评论真实导入** 后暴露的产品与架构问题，给出 **从现状到目标态的分步演进路径**，覆盖：

1. **导入层：** 多数据源（TapTap、小红书、客服工单等）如何适配进统一事实模型；  
2. **分类层：** L2 平台粗分 + L1 Workspace Topic Pack 细分 + 投影数据模型；  
3. **产品层：** 看板叙事、复核队列、告警副线；  
4. **扩展层：** 多游戏、多数据源、可选 LLM Skill。

阅读本文可理解「为什么这样设计、先做哪一步、后做哪一步」，不必在多个对话片段间拼接上下文。

---

## 2. 现状与问题（2026-07 真实数据）

### 2.1 数据侧

- TapTap 导入约 **1200+** 条评论；  
- 仅 **~25%** 命中全局 `issue-rules.toml` 8 类客服主题；  
- **~75%** 因 `unclassified` 进入人工复核队列（~900 条），运营无法处理；  
- 主产品诉求是 **舆情情绪 / 建议分布**，而非客服工单异常监控。

### 2.2 产品叙事错位

| 现状 | 问题 |
|------|------|
| 看板强调「主题 Top 5 / 告警」 | 大量 TapTap 长评不在 8 类工单主题内 |
| 「未分类 = 待人工复核」 | 把规则覆盖不足当成人工待办 |
| 「已纳入可行动主题 X%」类 KPI | 像系统失败指标，不符合舆情分析叙事 |
| 导入需频繁手动列映射 | TapTap 标准 CSV 表头已对齐，仍常进手动 Step 2 |

### 2.3 架构侧已有能力（可复用）

- `feedback_event`：脱敏反馈事实（导入终点）；  
- `feedback_issue_link`：反馈 × L1 议题关联（0～2 行，含议题级情绪）；  
- 投影流水线、EWMA 告警、Agent 调查 Tool、Workspace 隔离；  
- TapTap 导出脚本 → 固定 Canonical CSV 表头。

---

## 3. 目标态（North Star）

### 3.1 一句话

**任意数据源 → 统一 FeedbackEvent → L2 平台粗分（跨源/跨游戏可比）→ L1 Pack 细分（仅本 Workspace 钻取）→ 样本 / Agent / 告警副线。**

### 3.2 目标架构图

```text
┌──────────────── 数据源层 ─────────────────┐
│ TapTap 脚本 │ 小红书脚本 │ 客服 CSV │ …   │
└─────────────┬─────────────────────────────┘
              │ Adapter / Import Profile
              ▼
┌──────── Canonical Row（文件或内存）───────┐
│ feedback_text, occurred_at, source,       │
│ external_ref, dimensions?                 │
└─────────────┬─────────────────────────────┘
              │ CSV 上传 + 映射（可自动）
              ▼
┌──────── feedback_event（库内唯一事实）───┐
└─────────────┬─────────────────────────────┘
              │ Workspace 投影
              ▼
┌─ feedback_projection_annotation（L2 快照）─┐  每 event × 投影 1 行
├─ feedback_issue_link（L1 议题关联）──────┤  每 event × 投影 0～2 行
└─  metric buckets / 告警 / 复核候选 ──────┘
```

### 3.3 产品主路径

```text
Dashboard 首屏：L2 五类（建议 / 吐槽 / 好评 / 体验分享 / 其他）
    ↓ 点击某一 L2
二级：当前 Workspace Topic Pack 的 L1 议题分布（含 topic_general）
    ↓ 点击某一 L1
三级：L2×L1 交叉样本 + 议题级情绪
副屏：alert_eligible 议题的 EWMA 告警（可行动问题监控）
```

---

## 4. 核心设计原则

| 原则 | 说明 |
|------|------|
| **KISS / YAGNI** | 不做 Skill 市场、不做多 Pack A/B、不 v1 接 API 直连小红书 |
| **Workspace 隔离** | 导入、分类、复核、看板均按 Workspace |
| **平台稳定 + Pack 可插拔** | L2 与 canonical 导入格式平台统一；L1 词表与 Import Profile 按游戏/源配置 |
| **粗→细，不是主→副 KPI** | 不用「L1 覆盖率」羞辱指标；`topic_general` 是正常桶 |
| **映射能力保留、手动非常态** | 后端映射模型不删；标准 CSV 应零步导入 |
| **Agent 只读** | LLM 补分类不自动改规则/历史事实 |
| **重投影可追溯** | 分类事实绑定 `workspace_projection_id` 与规则/Pack 版本 |

---

## 5. 导入层设计

### 5.1 三层模型

| 层 | 职责 | 是否随数据源变化 |
|----|------|------------------|
| **原始格式** | 各平台 CSV / JSON / API 字段 | 是 |
| **Canonical Row** | 交换与校验用的统一行 | 否（平台契约） |
| **feedback_event** | 库内脱敏事实 | 否（已有实体） |

### 5.2 Canonical Row（库内 + 文件交换统一口径）

**必填四元组（与 `ImportMapping` 一致）：**

| 规范字段 | 写入 `feedback_event` | 说明 |
|----------|----------------------|------|
| `feedback_text` | `sanitized_text` / `normalized_text` | **唯一参与 L2/L1 的正文**；多字段源在 Adapter 内拼接 |
| `occurred_at` | `occurred_at` | ISO 8601 优先 |
| `source` | `source_kind` | 受控来源码，如 `taptap_review`、`xhs_post` |
| `external_ref` | `external_ref_hash` | 稳定外部 ID，仅哈希入库 |

**可选 `dimensions`：** 写入 `dimension_json`（rating、likes、tags、platform 等），供筛选与 Agent 引用，**不进入 L2/L1 核心逻辑**。

### 5.3 Canonical CSV v1（对外文件契约）

```csv
feedback_text,occurred_at,source,external_ref
"希望匹配快一点",2026-07-20T10:00:00+08:00,taptap_review,note_abc123
```

可选扩展列通过 Import Profile 映射进 `dimensions`，例如：`rating`, `platform`, `likes`, `tags`。

**TapTap 导出脚本** 已产出此格式，是标准参考实现（见 `2026-07-26-taptap-review-export-design.md`）。

### 5.4 多数据源适配：两条路径

#### 路径 A — 外部 Adapter 脚本（推荐主路径）

```text
tools/
  taptap-review-exporter/     → Canonical CSV（已有）
  xhs-post-exporter/          → Canonical CSV（未来）
  cs-ticket-export/           → Canonical CSV（未来）
```

- 抓取、鉴权、字段清洗、PII 剔除在 **主应用外** 完成；  
- 主应用仍走：上传 → 映射（可全自动）→ 异步导入；  
- 适合：小红书、反爬、结构复杂源。

#### 路径 B — Import Profile（上传原始 CSV 时的兜底）

与 Topic Pack 并列，按 **数据源类型** 配置列映射与拼文规则：

```toml
# config/import/profiles/xhs_post_v1.toml
profile_id = "xhs_post"
canonical_source = "xhs_post"

[column_mapping]
feedback_text = "note_text"
occurred_at = "publish_time"
source = "source"
external_ref = "note_id"

[dimensions]
likes = "like_count"
tags = "tags"

[compose.feedback_text]
template = "{title}\n{body}"
fields = ["title", "body"]
```

Workspace 可绑定默认 `import_profile_id`；与 `topic_pack_id` **独立**（同一游戏可同时导入 TapTap + 小红书）。

### 5.5 source_kind 约定

| source_kind | 含义 |
|-------------|------|
| `taptap_review` | TapTap 商店评论 |
| `xhs_post` | 小红书笔记 |
| `xhs_comment` | 小红书评论（若单独采集） |
| `cs_ticket` | 客服工单 |
| `appstore_review` | 应用商店评论 |

- 用 **`平台_类型`**，不在 source 里编码游戏名（游戏由 Workspace 表达）；  
- 看板 / Agent 可声明「本结论来自 xhs_post」。

### 5.6 列映射 UI：保留能力，降级为 fallback

| 层次 | 决策 |
|------|------|
| 后端 `ImportMapping` + 任务快照 | **保留** |
| 手动映射 Step 2 UI | **保留**，仅 auto-match 失败时出现 |
| 自动匹配优先级 | ① 表头与 canonical 名完全一致 → ② Import Profile → ③ 中文模糊（反馈/时间/…） → ④ 手动 |

---

## 6. 分类层设计

### 6.1 L2 平台表达层（粗分 · 全平台共用）

**回答：** 这条评论**整体在表达什么**。

| canonical_key | 中文名 |
|---------------|--------|
| `expr_suggestion` | 建议/诉求 |
| `expr_complaint` | 吐槽/不满 |
| `expr_praise` | 好评/推荐 |
| `expr_neutral` | 体验分享（短咨询暂并入） |
| `expr_other` | 其他 |

- 配置：`config/analysis/platform/expression-rules.toml`；  
- 每条评论 **必有** L2；目标 `expr_other` < 15%；  
- **不进人工复核**。

**建议 vs 体验分享（边界）：**

- **建议：** 含明确诉求（希望/建议/能不能/应该/优化…）→ 看「玩家想改什么」；  
- **体验分享：** 主要是经历陈述（玩了 X 小时/总体来说…），无诉求、无强正负；  
- 同分时优先级：`suggestion > complaint > praise > neutral > other`。

### 6.2 L1 Workspace Topic Pack（细分 · 按游戏）

**回答：** 在本游戏语境下，**在聊哪个议题**。

- **不在平台 core 写死** TapTap/某游戏的 10 类议题；  
- 每 Workspace 绑定一个 Pack（如 `game-chaoziran:v1`）；  
- Pack 含：`topic-catalog.toml`、`topic-rules.toml`、可选 `topic-normalize.toml`；  
- **必须**含 `topic_general`（综合/未指向具体议题）— 规则零命中时写入，**正常统计，不进复核**；  
- 每个 L1 可标 `alert_eligible`；仅此类参与 EWMA 告警。

**与「Skills」的关系：** MVP 中 Pack = **分类 Skill 包**（规则 toml 实现），不做 Skill 注册中心；LLM Topic Skill 为 Pack 可选能力（Phase C）。

**跨游戏：**

- **L2 可跨 Workspace 对比**（建议占比等）；  
- **L1 不可硬比**（除非共用同一 Pack 版本）；  
- 组织级汇总默认只聚合 L2。

### 6.3 主题级情绪（已有，延续 V21/V22）

- 挂在 **议题 link** 上，非整条评论一个情绪；  
- `mixed` 可触发 L1 复核；与 L2 `mixed_expression` **独立**。

### 6.4 人工复核（仅 L1 歧义）

| 进复核 | 不进复核 |
|--------|----------|
| `ambiguous_topics` | `unclassified`（已废止进复核） |
| `too_many_topics` | `topic_general` |
| 议题级 `mixed_sentiment` | L2 `expr_other` |

### 6.5 投影分类事实：两张表（已确认）

| 表 | 粒度 | 内容 |
|----|------|------|
| **`feedback_projection_annotation`** | 每 event × 投影 **1 行** | L2 + `expression_rule_version` + `topic_pack_id/version` |
| **`feedback_issue_link`** | 每 event × 投影 **0～2 行** | L1 议题关联 + 议题级情绪；**文档所称 link 仅指此表** |

不单独建 `feedback_expression` 表。

```text
feedback_event（导入源，与投影版本无关）
  ├─ feedback_projection_annotation  ← L2 怎么说
  └─ feedback_issue_link             ← L1 说啥议题（0～2）
```

---

## 7. 看板与 API 叙事

### 7.1 展示

- **首屏：** L2 占比 + L2 趋势 + 总评论数；  
- **二级：** 选中 L2 → Pack 内 L1 分布（含 `topic_general`）；  
- **三级：** L2×L1 样本；  
- **副屏：** `alert_eligible` 议题告警。

### 7.2 明确不展示

- 「已纳入可行动主题 X%」  
- 「l1Linked / l1Other」类覆盖率 KPI  

### 7.3 关键 API（目标）

| 路径 | 用途 |
|------|------|
| `GET .../dashboard` | L2 分布、趋势、reviewPending、pack 信息 |
| `GET .../expressions/{exprKey}/topics` | L2 下 L1 钻取 |
| `GET .../expressions/{exprKey}/topics/{topicKey}/samples` | 交叉样本 |

---

## 8. 分步演进路线图

以下阶段 **按顺序推进**；每阶段有独立验收，不要求一步到位。

### Phase 0 — 现状基线（已完成）

- [x] CSV 导入 + 映射 + 异步任务 + `feedback_event`  
- [x] 规则 L1 + 投影 + EWMA + 多主题情绪 + 复核候选  
- [x] TapTap 导出脚本 → Canonical CSV  
- [x] RAG / Agent 调查基础能力  

**遗留问题：** unclassified 淹没复核；叙事偏工单；TapTap 标准 CSV 自动映射不全。

---

### Phase A — L1 复核降噪 + topic_general（优先，~0.5～1 天 AI 编码）

**目标：** 立刻去掉 ~900 条无意义复核；L1 零命中仍有统计归属。

| 改动 | 说明 |
|------|------|
| `reviewReason()` | `unclassified` 不再创建复核候选 |
| 零 L1 命中 | 写 `topic_general` **议题 link**（非「不写 link」） |
| Data.vue | 复核 Tab 仅 ambiguous / too_many / mixed |
| 文案 | 「L1 议题歧义待确认」，不含 unclassified |

**验收：** 1200 条导入后复核 < 100；`topic_general` 可在议题列表/趋势中查到。

**不改：** L2 看板、Pack 目录、导入 auto-match。

---

### Phase B — 平台 L2 + Topic Pack + 粗→细看板（MVP 核心，~1～2 天）

**目标：** 产品主路径闭环 — 「建议/吐槽/好评分布 + 钻取议题」。

| 改动 | 说明 |
|------|------|
| `platform/expression-rules.toml` + `ExpressionClassifier` | L2 五类 |
| `config/analysis/packs/game-chaoziran/` | 首包 topic-catalog + topic-rules |
| Workspace 绑定 `topic_pack_id` | 配置字段 + 加载器 |
| Flyway `feedback_projection_annotation` | L2 快照表 |
| 投影写入 + `expression_metric_bucket` | L2 日聚合 |
| Dashboard | 首屏 L2；二级 L2→L1 钻取 |
| 旧 issue key | alias 映射进首包 |

**验收：**

- L2 非 other ≥ 85%；  
- Dashboard 可演示建议/吐槽/好评占比并钻取 L1；  
- 平台 Java 代码无 TapTap 专属议题常量；  
- `mvnw test` + 前端 test/build 通过。

---

### Phase B+ — 导入体验（可与 B 并行，小步）

**目标：** TapTap / Canonical CSV 零步映射。

| 改动 | 说明 |
|------|------|
| 自动匹配 | 表头 **完全一致** 于 `feedback_text` 等 canonical 名则跳过 Step 2 |
| 文档 | 发布 Canonical CSV v1 契约（本文 §5.3） |

**验收：** TapTap 导出 CSV 上传后直接进入 Step 3「开始导入」。

---

### Phase C — Pack 级 LLM Topic Skill（可选）

**目标：** 降低 `topic_general` 占比，不动 L2 主路径。

| 改动 | 说明 |
|------|------|
| Pack 开关 `topic_llm_skill_enabled` | 默认 false |
| 仅对 `topic_general` 子集 | 异步批量 + 置信度门控 |
| 低置信 | 仍落 `topic_general` |
| 禁止 | 自动改 Pack 规则或历史 link |

**验收：** general 占比下降；成本/耗时可观测；规则变更仍须人工 + Pack 升版。

---

### Phase D — Agent / 报告 / 第二数据源（可选）

**目标：** 叙事完整 + 验证多源扩展。

| 改动 | 说明 |
|------|------|
| Investigation Tool | `queryExpressionDistribution`、`queryTopicDistribution(exprKey)` |
| 报告段落 | L2 结构 + L2×L1 交叉 |
| Import Profile | `xhs_post_v1.toml` 示例 + Workspace 绑定 |
| 外部脚本 | `tools/xhs-post-exporter/` 产出 Canonical CSV（主应用不扩 API） |
| Genre Starter Pack | `_templates/genre-moba` 等（冷启动词表） |

**验收：** 能回答「建议类主要在聊什么议题」；第二 Pack 或第二 Import Profile 仅增配置即可试绑。

---

### Phase E — 告警与迁移收尾（可选）

**目标：** 告警只服务可行动议题；历史口径清晰。

| 改动 | 说明 |
|------|------|
| EWMA | 仅 `alert_eligible=true` 的 L1（`topic_general` 默认 false） |
| 看板副屏 | 「可行动议题异常」与 L2 首屏分离 |
| 文档 | 废止 2026-07-27 spec 中「unclassified 进复核」条款 |

---

## 9. 演进时间预期（AI 编码 + 用户验收）

| 档位 | 范围 | 日历（用户配合） |
|------|------|------------------|
| **止血** | Phase A | 0.5～1 天 |
| **MVP** | Phase A + B (+ B+) | 1～2 天 |
| **增强** | + Phase C 或 D 之一 | +1 天 |
| **完整** | A～E | 3～4 天 |

瓶颈在重投影、词表迭代与验收，不在代码行数。

---

## 10. 目录与配置目标态

```text
config/
  analysis/
    platform/
      expression-rules.toml              # L2，全平台
    packs/
      game-chaoziran/
        pack.toml
        topic-catalog.toml
        topic-rules.toml
      _templates/
        genre-moba/                      # Phase D+
  import/
    profiles/
      canonical_v1.toml                  # 标准四列自动映射
      taptap_review.toml                 # 可选，与 canonical 等价
      xhs_post_v1.toml                   # Phase D+
tools/
  taptap-review-exporter/                # 已有
  xhs-post-exporter/                     # Phase D+
```

---

## 11. 示例：小红书从 0 接入（Phase D Walkthrough）

1. **定义 source_kind：** `xhs_post`。  
2. **编写 Adapter 脚本：** 输出 Canonical CSV；`feedback_text = title + "\n" + desc`；丢弃 author 等 PII。  
3. **（可选）Import Profile：** 支持运营直接上传原始导出 CSV。  
4. **Workspace：** 继续绑定 `game-xxx` Topic Pack（L1 按游戏，不按平台）。  
5. **导入 → 投影：** 与 TapTap 相同流水线；看板 L2 可跨源汇总，L1 钻取仍在 Workspace 内。  

**不需要：** 为小红书改 `feedback_event` 表结构或平台 L2 枚举。

---

## 12. 废止与修订记录

| 原设计 | 新设计 |
|--------|--------|
| L1 = 全局 8 类可行动主题 | L1 = Workspace Topic Pack |
| unclassified 不写 link + 进复核 | 写 `topic_general` link，不进复核 |
| 「已纳入可行动主题 X%」KPI | L2 首屏 + L1 钻取，无覆盖率羞耻指标 |
| 单独 `feedback_expression` 表 | `feedback_projection_annotation` |
| 每数据源一套宽 CSV | Canonical Row + Adapter / Profile |
| 每次导入手动映射 | 标准 CSV 自动映射，手动 fallback |

---

## 13. 确认记录

- [x] 数据模型：`feedback_projection_annotation` + `feedback_issue_link`  
- [x] 导入思路：Canonical Row + Adapter（主）+ Import Profile（辅）  
- [x] 分类思路：平台 L2 + Workspace Topic Pack L1 + `topic_general`  
- [x] 演进顺序：Phase A → B →（B+）→ C/D/E 按需  
- [ ] 首包 `game-chaoziran:v1` 议题表定稿  
- [ ] Phase A 开工授权  

---

## 14. 相关 Todo

执行跟踪见 [`docs/agent-optimization-todo.md`](../../agent-optimization-todo.md) 章节「L2 平台粗分 + Workspace Topic Pack 细分」。

分类表结构与 API 细节见 [`2026-07-28-l2-expression-l1-topic-layer-design.md`](2026-07-28-l2-expression-l1-topic-layer-design.md)（与本文保持一致；若冲突以本文演进路线为准）。
