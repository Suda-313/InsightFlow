# Data Cell 与规则优先主题归并设计

> 状态：待用户复核
> 日期：2026-07-20
> 分支：feature/data-cell-rule-issue-merging（待创建）
> 前置文档：`docs/HANDOFF.md`、`AGENTS.md`、`docs/superpowers/specs/2026-07-20-workspace-analysis-report-and-alert-design.md`
> 参考实现：`D:\ticket_automation_system-main`（Python 原型，cell/Alert 参数同源）

## 1. 目标与边界

将已投影的脱敏 `FeedbackEvent` 以确定性方式映射为可追溯主题事实，但暂不计算趋势、EWMA 或 Alert。本期为后续指标计算提供 `feedback_issue_link`、`data_cell`、`cell_issue` 三类可追溯事实。

**本阶段实现**（HANDOFF §7 步骤 1–5）：

1. 版本化 `config/analysis/issue-rules.toml` + `config/analysis/issue-normalize.toml`；
2. 纯 `RuleFirstIssueClassifier`，未命中返回 `unclassified`，不伪造主题；
3. `IssueTextNormalizer` 归一化层（全角半角/繁简/标点/同义词）提升规则召回；
4. `DataCellBuilder`：40 条 / 60 分钟 / 6000 token 任一达到即关闭 Cell；
5. 在投影 Worker 内写 `issue_catalog`、`issue_alias`、`feedback_issue_link`、`data_cell`、`cell_issue`；写入必须与 `WorkspaceProjection` 的成功事务一致；
6. 为规则命中、未命中、Cell 边界、Workspace 隔离和幂等重试写测试。

**本阶段不实现**（推到下一阶段）：`issue_metric_bucket` 日指标、`issue_baseline_profile` EWMA、`alert` z-score/冷却。`rebuild_required`（晚到历史数据）标记也留给下阶段基线判定，本期不写 baseline。

**不可突破的边界**（HANDOFF §8 / spec §8）：

- 不保存原始 CSV、真实工单号、手机、邮箱或未脱敏文本到 PostgreSQL；
- 不接 Qwen、不让 LLM 写库/创建 Alert/改基线；本期 `RuleFirstIssueClassifier` 是唯一分类实现；
- 报告重跑 ≠ 数据重建；本期不实现报告；
- 不在 Controller 或子类手写跨层 SQL、主题计算或状态机；
- 不执行 `git reset --hard`，不删用户未提交修改。

## 2. 架构与组件边界

把"主题归并 + Data Cell 事实写入"插进现有投影链路，不改 CSV 导入、不改 V1–V6 迁移、不碰 Qwen/Alert/EWMA。新增组件全部落在 `task/` 与 `service/` 包内，遵循 MVC 包结构与 Workspace 隔离。

```text
WorkspaceProjectionTaskRunner.run()  （现有入口，租约校验后调用执行服务）
  └─ WorkspaceProjectionExecutionService   【新增·编排·单事务】REQUIRES_NEW
       ├─ ProjectionSourceLoader           【新增】按 projection_file 读 FeedbackEvent，按 occurred_at 升序
       ├─ IssueRulesLoader                 【新增】加载 config/analysis/issue-rules.toml，暴露 rule_version
       ├─ IssueTextNormalizer               【新增】全角半角/繁简/标点/同义词归一
       ├─ RuleFirstIssueClassifier          【新增】排除→优先级→正向；最多2主题；ambiguous/unclassified
       ├─ DataCellBuilder                   【新增】40条/60min/6000token 三护栏切分，输出 close_reason
       ├─ IssueCatalogService               【新增】find-or-create issue_catalog + issue_alias（Workspace 私有）
       └─ ProjectionFactWriter              【新增】写 feedback_issue_link / data_cell / cell_issue（幂等）
 WorkspaceProjectionCompletionService      （现有·收口）成功→markProjected+终态；失败→projection_failed
```

**职责硬边界**（对应 spec §4 与 AGENTS.md）：

- `WorkspaceProjectionTaskRunner` 只编排、不写 SQL/规则/EWMA 公式；
- `WorkspaceProjectionExecutionService` 是单事务编排者，不跨层调用 Controller；
- `RuleFirstIssueClassifier` 是纯函数式分类，无 DB 依赖；DB 写入只在 `IssueCatalogService`/`ProjectionFactWriter`；
- `IssueClassifier` 作为 Port 接口保留，`RuleFirstIssueClassifier` 是本期唯一实现，后续 Qwen 实现只处理未命中/歧义。

## 3. 数据流、事务边界与幂等

### 3.1 数据流（单次投影执行）

```text
1. Scheduler 租约 → WorkspaceProjectionTaskRunner.run(publicId, workerId)
2. 校验租约归属（现有逻辑）
3. WorkspaceProjectionExecutionService.execute(projection)  【REQUIRES_NEW 单事务】
   a. 幂等守卫：查 data_cell 是否已存在该 projection_id 的事实
      - 已存在 → 直接返回 success（仅补状态收口，不重写）
      - 不存在 → 进入写入
   b. ProjectionSourceLoader 按 projection_file 读取全部 FeedbackEvent
      （只读 sanitized_text + occurred_at + public_id，Workspace 内）
   c. 逐条 IssueTextNormalizer.normalize → 规范文本
   d. RuleFirstIssueClassifier.classify(规范文本)
      → 0/1/2 个 issue + assignment_method ∈ {"rule","ambiguous"} + confidence（rule=1.0，ambiguous=0.5）
   e. DataCellBuilder.split(events) → 按 occurred_at 升序切分
      close_reason ∈ {count_limit, window_limit, token_limit, stream_end}
   f. IssueCatalogService.findOrCreate(canonical_key)
      → issue_catalog（workspace_id+canonical_key 唯一）+ issue_alias(origin="rule")
   g. ProjectionFactWriter 写：
      - feedback_issue_link  （唯一约束: projection×event×issue）
      - data_cell            （window_start/end, close_reason, event_count, estimated_tokens）
      - cell_issue           （唯一约束: cell×issue, mention_count, sample_event_ids JSONB[]）
      全部在本事务内，未提交前对其他事务不可见
   h. 事务提交 —— 任一步抛异常 → 整体回滚，0 部分事实落库
4. 成功 → WorkspaceProjectionCompletionService.complete() 【REQUIRES_NEW】
   - markProjected（import_file.projection_status = projected）
   - task.markSucceeded / projection.markSucceeded(projected_at)
5. 失败 → WorkspaceProjectionCompletionService.fail() 【REQUIRES_NEW】
   - import_file.projection_status = projection_failed
   - task.markFailed / projection.markFailed(error_code, message)
   - 已回滚的事实不残留；租约到期可重试，重试走步骤 3a 幂等守卫
```

### 3.2 事务边界决策

- **执行事务**（步骤 3）与**收口事务**（步骤 4/5）**分离**：执行事务承担"事实写入原子性"，收口事务承担"终态翻转"。两者都是 `REQUIRES_NEW`，互不影响。
- 这样保证：事实写入失败时收口不会误标 `projected`；事实写入成功但收口崩溃时，步骤 3a 守卫让重试安全跳过已写事实，只补状态。

### 3.3 幂等保证（对应 spec §7）

| 重复场景 | 保护机制 |
|---|---|
| 同文件+同规则版本重复创建投影命令 | `WorkspaceProjectionCommandService` 幂等键 `projection:file:{id}:{ruleVersion}` 返回已有记录 |
| 投影任务租约到期重试，事实已写未收口 | 步骤 3a 守卫查 `data_cell` 存在即跳过写入 |
| 重试时部分 link 已写（理论不应发生，因单事务） | `feedback_issue_link` 唯一约束 `(projection, event, issue)` 防重复 |
| 同 cell+issue 重复 | `cell_issue` 唯一约束 `(data_cell_id, issue_id)` 防重复 |

## 4. 归一化、分类器与 DataCell 算法

### 4.1 IssueTextNormalizer 归一化管线

纯函数，无 DB 依赖。处理顺序固定（后步依赖前步）：

```text
原始 sanitized_text
 → 1. 全角→半角（字母/数字/标点）
 → 2. 繁体→简体（CJK 繁简表，游戏词常用字）
 → 3. 去冗余空白与连续标点（保留单分隔符）
 → 4. 大小写统一（ASCII lowercase）
 → 5. 同义词归一映射（issue-normalize.toml，子串替换）
       例：登不上/登不进去/上不去/卡在登录 → 登录失败
 → 6. 输出 normalized_text（与原文本等长近似，仅用于匹配，不落库）
```

- 归一表 `config/analysis/issue-normalize.toml`：`[[mappings]] from = ["登不上","上不去"] to = "登录失败"`；版本号随 `issue-rules.toml` 一起进 `rule_version`。
- 归一**只用于匹配**，`feedback_issue_link` 不存原文也不存归一文本，只存 `issue_id` + `confidence`，满足"不存未脱敏文本"边界（原文已是脱敏 `sanitized_text`）。

### 4.2 RuleFirstIssueClassifier 匹配算法

```text
输入：normalized_text
输出：List<Classification>（0..2 条），每条 {canonical_key, confidence, assignment_method}

1. 遍历全部规则，对每条规则：
   a. 若 normalized_text 命中该规则的任一 exclude_patterns（子串）→ 该规则出局
   b. 否则统计 any_patterns 命中数 hits（去重计词）
      - 若支持 all_patterns：需全部命中才算该规则候选
      - 否则 hits>0 即候选
2. 候选规则按 (priority DESC, hits DESC, 最长命中词长度 DESC) 稳定排序
3. 取前 2 条作为关联：
   - 若第 2 名与第 1 名在 priority+hits 完全同分 → 第 2 条标记 assignment_method="ambiguous"
   - confidence：rule 命中=1.0（确定性匹配封顶）；ambiguous=0.5（同分并列，不强行二选一）
4. 无任何候选 → 返回空列表，调用方记为 unclassified（不写 feedback_issue_link）
```

- "最多 2 主题"由取前 2 强制保证；同分 ambiguous 不强行二选一（对应 spec §4）。
- `unclassified` 不产生 `cell_issue` 行；只在 `data_cell.event_count` 中计入总数，看板摘要层后续单独统计（spec §4："unclassified 数进入看板摘要，但不进入主题趋势和 Alert"）。

### 4.3 DataCellBuilder 切分算法

```text
输入：List<Event>（已按 occurred_at 升序），每条含 normalized_text
配置：MAX_COUNT=40, MAX_WINDOW=60min, TOKEN_BUDGET=6000
输出：List<DataCell>，每个含 {window_start, window_end, close_reason, event_count, estimated_tokens, events}

token 估算（对齐原型 cell_windowing.estimate_tokens）：
  cjk = CJK 字符数；other = 其余字符数
  estimated_tokens = floor(cjk/1.5 + other/4) + 1

切分逻辑：
  current_cell = []; token_sum = 0; window_start = null
  for event in events:
      if current_cell 非空:
          span = event.occurred_at - window_start
          would_exceed_count  = len(current_cell) >= 40
          would_exceed_window = span >= 60min
          would_exceed_token  = token_sum + event.tokens > 6000
          if 任一为真:
              close current_cell（close_reason = 触发的那个；多触发取优先级 count>window>token）
              start new cell；reset token_sum, window_start
      if current_cell 空: window_start = event.occurred_at
      current_cell.append(event); token_sum += event.tokens
  收尾：若 current_cell 非空 → close_reason = "stream_end"
```

- `close_reason` 枚举：`count_limit` / `window_limit` / `token_limit` / `stream_end`，落 `data_cell.close_reason`。
- 单条事件 token 超过 6000 的极端情况：该事件独占一个 cell，close_reason=`token_limit`，不丢弃。
- `cell_issue.sample_event_ids`：每主题每 cell 最多保留 5 条 `feedback_event` 的内部 BIGINT id（JSONB 数组），用于证据回溯，不存文本。

### 4.4 落库字段映射（本期 5 张表）

| 表 | 本期写 | 下阶段写 |
|---|---|---|
| `issue_catalog` | ✅ canonical_key/name/status/first_seen/last_seen | — |
| `issue_alias` | ✅ normalized_alias/origin="rule" | 后续 origin="llm"/"manual" |
| `feedback_issue_link` | ✅ event↔issue, projection, assignment_method, confidence | — |
| `data_cell` | ✅ window/close_reason/event_count/estimated_tokens | — |
| `cell_issue` | ✅ mention_count/sample_event_ids | — |
| `issue_metric_bucket` | ❌ 不写 | 下阶段 |
| `issue_baseline_profile` | ❌ 不写 | 下阶段 |
| `alert` | ❌ 不写 | 下阶段 |

## 5. 初始规则集

`src/main/resources/config/analysis/issue-rules.toml` 种子规则（canonical_key 来自游戏工单领域类目 `bug/payment/account/report/suggestion`，参考原型 prompts.py 的 category 体系与 settings.toml 的 PROBLEM_CATEGORIES）：

```toml
# issue-rules.toml 规则优先主题归并；未命中返回 unclassified，不伪造主题。
version = "rule_v1"

[[rules]]
canonical_key = "login_failure"
name = "登录失败"
priority = 90
any_patterns = ["登录不上", "登录失败", "登不上", "进不去游戏", "无法登录", "登录异常"]
exclude_patterns = ["充值", "退款"]

[[rules]]
canonical_key = "payment_recharge"
name = "充值异常"
priority = 80
any_patterns = ["充值", "到账", "未到账", "充了没收到", "充值失败"]
exclude_patterns = []

[[rules]]
canonical_key = "item_loss"
name = "道具丢失或异常"
priority = 70
any_patterns = ["道具", "没了", "消失", "丢失", "扣了", "异常消失"]
exclude_patterns = ["充值"]

[[rules]]
canonical_key = "account_recovery"
name = "账号找回"
priority = 70
any_patterns = ["账号找回", "找回账号", "换绑", "账号丢失", "账号异常"]
exclude_patterns = ["登录失败"]

[[rules]]
canonical_key = "bug_gameplay"
name = "玩法bug"
priority = 60
any_patterns = ["bug", "卡死", "闪退", "报错", "玩法", "打不开", "卡住"]
exclude_patterns = ["充值", "登录"]

[[rules]]
canonical_key = "bug_network"
name = "网络问题"
priority = 55
any_patterns = ["网络", "延迟", "掉线", "卡顿", "460", "断线"]
exclude_patterns = []

[[rules]]
canonical_key = "violation_report"
name = "违规举报"
priority = 40
any_patterns = ["举报", "挂机", "外挂", "辱骂", "违规", "作弊"]
exclude_patterns = []

[[rules]]
canonical_key = "suggestion"
name = "建议反馈"
priority = 30
any_patterns = ["建议", "希望", "能不能", "可不可以", "提议"]
exclude_patterns = ["bug", "闪退"]
```

同义词归一表 `src/main/resources/config/analysis/issue-normalize.toml`（种子示例，可扩展）：

```toml
version = "rule_v1"

[[mappings]]
from = ["登不上", "登不进去", "上不去", "进不去游戏", "卡在登录", "登入不了"]
to = "登录失败"

[[mappings]]
from = ["充了没到", "充值没到账", "充值没收到"]
to = "充值失败"

[[mappings]]
from = ["掉东西", "东西没了"]
to = "道具消失"
```

规则集说明：匹配对象是脱敏后的 `sanitized_text`（中文）；一条反馈最多关联 2 个主题；按优先级降序匹配，先排除 `exclude_patterns` 再匹配正向；完全无命中返回 `unclassified`，不伪造主题。改规则即升 `version`，触发 `workspace_projection.rule_version` 变更与重新投影。

## 6. 错误处理与失败语义

| 失败场景 | 处理 | 状态结果 |
|---|---|---|
| `issue-rules.toml` 解析失败/缺规则 | 启动期 `@PostConstruct` 校验，Bean 初始化失败，应用不启动 | 启动期暴露，不进运行期 |
| 归一化/分类器抛异常（理论不应，纯函数） | 执行事务回滚 → `fail("PROJECTION_CLASSIFY_FAILED")` | `projection_failed`，可重试 |
| `ProjectionSourceLoader` 找不到事件（文件已 projected 但事件丢失） | `fail("PROJECTION_SOURCE_EMPTY")` | `projection_failed`，人工排障 |
| `IssueCatalogService`/`ProjectionFactWriter` DB 异常 | 执行事务整体回滚，0 部分事实 → `fail("PROJECTION_FACT_WRITE_FAILED")` | `projection_failed`，租约重试走幂等守卫 |
| 同一 Workspace 已有 running 投影 | `WorkspaceProjectionCommandService` 队列串行（现有） | 排队，不并发 |
| 晚到历史数据（occurred_at 早于已提交基线） | 本期不判基线，按 occurred_at 升序正常切 cell | 正常 projected；`rebuild_required` 标记留给下阶段 |

- 所有 `error_code`/`error_message` 只含脱敏摘要，不泄露其他 Workspace。
- 失败永远不回滚 `import_file.status=processed`（CSV 已安全导入的事实保留），只影响 `projection_status`。
- 重试由 `async_task` 租约状态机驱动，不新增重试机制（对应 spec §7）。

## 7. 测试策略（TDD：红-绿-重构，先写失败测试）

测试全部落 `src/test/java/com/insightflow/`，沿用现有测试风格。分类器/归一化/Cell 切分用纯单元测试（不依赖 DB）；集成测试用 `@SpringBootTest` + 事务回滚。

| 测试组 | 覆盖（对应 spec §8 / HANDOFF §7 步骤5） |
|---|---|
| `IssueTextNormalizerTest` | 全角半角、繁简、同义词归一、不改原文语义的边界 |
| `RuleFirstIssueClassifierTest` | 命中、未命中→unclassified、同分→ambiguous、最多2主题、exclude 排除、priority 排序 |
| `DataCellBuilderTest` | 40条边界、60min边界、6000token边界、stream_end、单条超 token 独占 cell、空输入 |
| `IssueRulesLoaderTest` | toml 解析、规则版本号、空规则集启动失败 |
| `WorkspaceProjectionExecutionServiceTest`（集成） | 写 5 表成功、失败回滚无残留、幂等守卫（二次执行不重写）、Workspace 隔离（两 Workspace 主题不串） |
| `IssueCatalogServiceTest` | find-or-create 幂等、canonical_key 唯一约束、alias 不重复 |

验证收尾命令（HANDOFF 要求）：`.\mvnw.cmd test` + `.\mvnw.cmd package`。

**注释要求**（AGENTS.md）：每个新增业务/实体/迁移模块有效注释行数 ≥ 非空代码行数 1/2，解释业务目的/约束/边界，禁止机械复述；先写失败测试再写最小实现。

## 8. 非目标与后续演进

本期不实现：真实 Qwen 调用、`issue_metric_bucket` 日指标、`issue_baseline_profile` EWMA、`alert` z-score/冷却、`rebuild_required` 晚到数据标记、按需分析报告、看板查询 API。它们均建立在本期形成的 `issue_catalog` / `feedback_issue_link` / `data_cell` / `cell_issue` 事实之上。

后续接入 Qwen 时，模型只作为 `IssueClassifier` 的受限补充，处理未命中或歧义输入；只能选择已有主题、返回 `new_candidate` 或 `unclassified`，不得直接创建主题、修改指标或改写 Alert。
