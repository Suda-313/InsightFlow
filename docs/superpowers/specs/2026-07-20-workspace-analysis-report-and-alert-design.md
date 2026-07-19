# Workspace 看板投影、按需报告与预警设计

> 状态：待用户复核
> 日期：2026-07-20
> 前置文档：`D:\舆情agent文档\03-architecture.md`、`04-data-model.md`、`05-agent-design.md`、`06-api-contract.md`、`08-implementation-plan.md`

## 1. 目标与边界

CSV 成功导入后，系统自动将脱敏反馈投影为 Workspace 分析看板需要的主题、趋势、基线和预警；用户无需再点击“分析”才能在看板看到舆情数据和告警。

“分析报告”是另一条按需、只读的解释链路。用户可选择若干已导入文件或时间范围生成报告，也可随时以相同或新的范围重新生成。报告重跑不写入反馈、主题指标、EWMA 基线或 Alert，因此不会造成重复累计或重复告警。

本期实现确定性的 Data Cell、规则优先主题归并、按日指标、EWMA/z-score、冷却期、看板查询和结构化报告。它不接入真实 Qwen、不生成自然语言 LLM 报告、不调用 Agent Tool、不做持续外部监控、Excel、爬虫、多 Agent 或前端页面。

原始 CSV 始终只留在 MinIO；分析只读取已脱敏的 `FeedbackEvent`。所有文件、投影、主题、基线、预警和报告查询均强制带 `workspace_id`。

## 2. 产品流程

```text
上传 CSV → 字段映射 → 点击开始导入
                       ↓
               ImportTask 写入脱敏 FeedbackEvent
                       ↓
            自动创建 WorkspaceProjectionTask（串行）
                       ↓
       Data Cell → 主题归并 → 日指标 → EWMA → Alert
                       ↓
               分析看板同步展示数据与预警

用户按需选择文件或时间范围 → 创建 ReportRun
                               ↓
                 读取既有看板事实与有限脱敏样本
                               ↓
                 生成结构化报告；允许随时重新生成
```

导入是数据进入系统的唯一入口；导入完成即自动进入看板投影。报告不承担“确认数据是否进入历史记忆”的职责，而是对已经投影的事实做一次可追溯快照。

首次可用历史数据建立基线时为 `baseline_building`：看板照常显示主题和趋势，但不创建正式 Alert。后续投影必须以当前批次写入前的基线判断异常，再更新 EWMA。每个 Workspace 同时最多执行一个投影任务，以保证基线按时间顺序演进。

## 3. 数据模型

在现有 `async_task`、`import_file`、`feedback_event` 上新增或扩展以下模型。内部投影任务和用户可见报告分离，避免把一次“生成报告”误解为重新计算业务事实。

| 模型 | 关键字段与约束 | 用途 |
|---|---|---|
| `workspace_projection` | `id`、`public_id`、`workspace_id`、`async_task_id`、`status`、`rule_version`、`source_window_start/end`、`baseline_snapshot_at`、`projected_at` | 自动投影的执行快照与审计记录 |
| `projection_file` | `workspace_projection_id`、`import_file_id`；同一投影内唯一 | 固化本次自动投影处理的来源文件 |
| `import_file.projection_status` | `pending / projecting / projected / rebuild_required / projection_failed` | 保证每个成功导入文件最多进入一次增量投影 |
| `analysis_report` | `id`、`public_id`、`workspace_id`、`async_task_id`、`status`、`report_version`、`source_snapshot_at`、`report_json` | 用户主动生成的只读报告快照 |
| `analysis_report_file` | `analysis_report_id`、`import_file_id`；同一报告内唯一 | 固化报告选择的文件范围；时间范围报告可为空 |
| `issue_catalog` | `workspace_id`、`canonical_key`、`canonical_name`、状态和首末出现时间；`(workspace_id, canonical_key)` 唯一 | Workspace 私有稳定主题目录 |
| `issue_alias` | `workspace_id`、`issue_id`、规范化别名、`origin=rule` | 规则表达和后续人工/LLM 别名的可追溯载体 |
| `feedback_issue_link` | `workspace_id`、`feedback_event_id`、`issue_id`、`workspace_projection_id`、`assignment_method`、置信度、状态 | 反馈到主题的可追溯关联 |
| `data_cell` / `cell_issue` | 投影、时间窗、关闭原因、事件数、token 估算；每 Cell/主题的计数和有限样本引用 | 控制投影粒度并提供证据 |
| `issue_metric_bucket` | `workspace_id`、`issue_id`、日时间桶、反馈数、维度摘要、`workspace_projection_id` | 看板趋势、报告与预警的确定性事实 |
| `issue_baseline_profile` | `workspace_id`、`issue_id`、`active_buckets`、`baseline_ewma`、`baseline_variance`、`last_processed_bucket`、状态 | 每个主题的持久化历史基线 |
| `alert` | `workspace_id`、`issue_id`、`workspace_projection_id`、时间桶、当前值、基线、标准差、z-score、有效阈值、状态、证据摘要 | 可解释的预警记录 |

报告创建不改变 `import_file.projection_status`。一份已投影文件可出现在任意多个报告中；同一文件则只能由一个成功的增量投影处理。所有任务采用 `async_task` 的租约、重试和状态机，不重复发明后台任务机制。

## 4. 投影 Engine 与组件边界

`WorkspaceProjectionTaskRunner` 是轻量 Engine，只负责编排、状态推进、事务边界和结果收口，不直接编写 SQL、规则匹配或 EWMA 公式。`AnalysisReportTaskRunner` 只读取已投影事实并组装报告，绝不调用写指标或预警的服务。

```text
ImportTaskRunner
└─ ImportCompletedPublisher
   └─ WorkspaceProjectionCommandService      # 为 pending 文件创建幂等投影任务
      └─ WorkspaceProjectionTaskRunner
         ├─ ProjectionSnapshotLoader         # 校验文件、事件与基线快照
         ├─ DataCellBuilder                  # 按时间、数量、token 预算切分 Cell
         ├─ RuleFirstIssueClassifier          # 规则优先主题归并
         ├─ IssueCatalogService               # 查询或创建 Workspace 私有主题
         ├─ MetricBucketService               # 幂等写入日指标和维度摘要
         ├─ EwmaBaselineService               # 判定后提交基线
         └─ AlertDetector                     # 预警阈值与冷却判断

AnalysisReportTaskRunner
├─ ReportScopeLoader                          # 校验文件或时间范围
└─ AnalysisReportAssembler                    # 读取看板事实并生成结构化 JSON
```

规则放在版本化资源 `config/analysis/issue-rules.toml`。每条规则含 `canonical_key`、名称、优先级、正向 `any_patterns`、可选 `all_patterns` 和 `exclude_patterns`。规则命中后，系统在当前 Workspace 查找或创建同 key 的 `issue_catalog`，并建立 `feedback_issue_link`。

一条反馈最多关联两个主题。候选规则先排除 `exclude_patterns`，再按优先级、正向命中数量和最长命中词稳定排序；完全同分为 `ambiguous`，无匹配为 `unclassified`。二者不伪造主题链接；`unclassified` 数进入看板与报告摘要，但不进入主题趋势和 Alert。

未来的 `IssueClassifier` Port 保留给 Qwen：`RuleFirstIssueClassifier` 是本期唯一实现；Qwen 只能处理未命中或歧义输入，只能选择已有主题、返回 `new_candidate` 或 `unclassified`，不得直接创建主题、修改指标或改写 Alert。

## 5. Data Cell、历史基线与 Alert

投影输入按 `occurred_at` 升序排序；满足任一条件关闭一个 Data Cell：40 条反馈、60 分钟时间窗、估算 6000 token 或输入结束。token 估算基于 `sanitized_text` 的保守字符估算，不调用模型。

每个主题按事件发生日写入 `issue_metric_bucket`。预警计算使用当前投影写入前冻结的 `issue_baseline_profile`，而不是先把本批数据写入基线，防止异常数据抬高自己的阈值。

对当前日主题计数 `x`、历史 EWMA `B`、历史方差 `V`：

```text
sigma = sqrt(max(V, 0))
z_score = (x - B) / max(sigma, 1)
effective_threshold = max(global_floor, policy_floor, round(B + z_threshold × sigma))
```

当基线桶数达到 `MIN_HISTORY_BUCKETS`，且当前值同时满足 `x >= effective_threshold` 与 `z_score >= z_threshold` 时，创建 Alert。默认配置为：`alpha=0.3`、`z_threshold=2.0`、`global_floor=5`、`MIN_HISTORY_BUCKETS=7`、冷却期 6 小时。

Alert 创建后，以 `(workspace_id, issue_id)` 为键检查最近 Alert。冷却期内不重复创建；冷却结束后再次满足条件才允许新 Alert。后续 Agent 采纳的 `alert_policy` 可提高单主题阈值或修改冷却期，但本期仅保留读取兼容，不实现策略编辑。

预警判定结束后，才按日时间桶顺序更新 EWMA：

```text
B_next = alpha × x + (1 - alpha) × B
V_next = alpha × (x - B)^2 + (1 - alpha) × V
```

首次基线构建只提交指标和 Profile；不创建 Alert。若导入文件含早于已提交历史的晚到数据，自动投影标记该文件为 `rebuild_required`，不直接改写基线或指标。用户仍可基于当前已投影看板生成只读报告；完整“重建 Workspace 基线”留到后续受控能力。

## 6. API、看板与报告契约

导入接口不新增“是否分析”的开关。导入成功后，系统自动创建或合并对应的投影任务，客户端通过现有任务查询接口获知 `projection_status`。

```text
GET /api/v1/workspaces/{workspaceId}/dashboard
→ 看板摘要：数据覆盖时间、主题 Top N、趋势、当前 Alert、基线状态、最近投影状态

GET /api/v1/workspaces/{workspaceId}/issues
GET /api/v1/workspaces/{workspaceId}/issues/{issueId}
→ 主题目录、近期指标、有限脱敏样本与关联 Alert

POST /api/v1/workspaces/{workspaceId}/analysis-reports
Header: Idempotency-Key
Body: { "file_ids": ["..."], "time_range": { "start": "...", "end": "..." } }
→ 202 Accepted，返回 AnalysisReport / AsyncTask 公开 ID

GET /api/v1/workspaces/{workspaceId}/analysis-reports/{reportId}
→ 状态、范围快照、结构化报告、所引用的 Alert 摘要与失败摘要
```

报告请求至少指定 `file_ids` 或 `time_range` 之一；二者同时存在时取交集。文件必须属于当前 Workspace 且 `projection_status=projected`。后端不使用“当前所有文件”的隐式范围。相同范围可用新的 Idempotency-Key 创建新的报告快照；这就是面向用户的“重新分析”，只读且安全。

报告初版是结构化 JSON，至少包括：范围与文件摘要、输入/去重/未分类计数、时间覆盖范围、主题 Top N、主题计数与有限脱敏样本、维度摘要、基线状态、当前 Alert 摘要、规则版本、看板数据截点与 Report 标识。它不包含 LLM 生成的结论或因果判断。

## 7. 失败、幂等与安全

- `ImportTask` 仅在成功写入全部脱敏 `FeedbackEvent` 后，才创建自动投影命令；
- 同一 Workspace 同时只允许一个 `created / queued / running` 的投影任务，保证 EWMA 提交顺序；
- 相同来源文件和规则版本的投影命令返回已有未完成或成功的 `WorkspaceProjection`，不重复累计主题指标、EWMA 或 Alert；
- 投影失败不得提交部分主题指标、Profile 或 Alert；来源文件保留 `projection_failed`，可由任务重试恢复；
- 报告失败不影响看板事实、基线与 Alert；重试复用同一个 Report 快照；
- 所有样本、报告、Trace 和错误摘要只含脱敏文本；
- 跨 Workspace 文件选择、未投影文件、空范围和不存在资源返回受控 422/404，不泄露其他 Workspace 信息。

## 8. 验收与测试

1. 成功导入一份 CSV 后，无需创建报告，自动投影完成且 Dashboard 出现主题和数据覆盖范围；
2. 同一 Workspace 连续导入三份文件，投影串行执行，趋势指标和 EWMA 不重复累计；
3. 首次历史文件投影进入 `baseline_building`，Dashboard 有主题趋势但无正式 Alert；
4. 后续新文件的 `login_failure` 显著高于投影前基线时，Dashboard 出现一个 Alert；
5. 冷却期内再次出现相同异常不创建第二个 Alert；
6. 创建报告后，指标、Profile、Alert 数和各文件 `projection_status` 完全不变；
7. 使用新的 Idempotency-Key 重新生成同一范围报告，只创建新的报告快照，不重复累计或告警；
8. 两个 Workspace 的主题、基线、看板、报告和 Alert 完全隔离；
9. 规则未命中数据进入 `unclassified`，不产生虚假主题；
10. 晚到历史数据被标记 `rebuild_required`，不直接污染既有基线。

## 9. 非目标与后续演进

本期不实现真实 Qwen 调用、LLM 报告润色、自动持续外部监控、Alert 策略编辑、人工采纳、Agent 调查、SSE、前端多选页面、基线重建和报告导出。它们均建立在本期自动投影形成的主题、指标、Alert 与可追溯报告之上。

后续接入 Qwen 时，模型只作为 `IssueClassifier` 的受限补充和报告解释层；确定性主题计数、EWMA、z-score、冷却和 Alert 仍由本设计中的代码与人工策略控制。用户点击“重新分析”始终默认重生成报告；只有受控的“重建数据”流程才允许重算看板事实和基线。
