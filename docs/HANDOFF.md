# InsightFlow 开发交接

> 最后更新：2026-07-30  
> 当前任务：运营调查型 RAG 检索优化与低成本评测分层  
> 完整执行方案：`docs/agent-optimization-todo.md` → **G4 / Step R（修订版）**

## 当前结论

- 人工金标数据集 `ops-rag-v1/dev-240` 已完成一次 240/240 端到端执行；历史批次 `1f18b3a3-43bb-6046-b7d2-7124cd3c9991` 可用于排障，但其中部分指标受旧评分/carry-forward 逻辑影响，不能作为新检索版本的最终门禁。
- Step M（度量修复）代码已完成：拒答率分子、document/chunk Recall 解耦、长断言匹配、carry-forward hit@K/MRR/nDCG/耗时字段、`latencySampleCount`。
- Step M 相关定向测试已通过；**尚未在新代码下全量重跑 dev-240**，因此 `baseline-metrics-v2` 尚未产生。
- 用户已确认采用“候选召回 → Candidate Recall → 专用精排 → 软多样性 → Top8”方案，并同意增加 retrieval-only 评测漏斗，避免每次调参都调用 240 次聊天模型。

## 下一模型从这里开始

严格按以下顺序执行，不要直接开始 Cross-encoder，也不要先改 Prompt：

1. 全量跑一次当前 `dev-240`，记录 Step M 修复后的 `baseline-metrics-v2`。
2. 实现 G4/R0：
   - `retrieval-only` 模式；
   - 固定 `dev-fast-40` / `dev-e2e-30` case-key 文件；
   - Candidate Recall@10/30/50 与候选来源指标；
   - 评测专用 query embedding 缓存。
3. 实现 G4/R1：
   - lexical Top40 + vector Top40 → RRF Top30～50；
   - 标题、文档类型、版本、章节进入 lexical 候选文本；
   - 确定性版本号/KI 编号/日期 query expansion；
   - planner 关键词补齐。
4. 先用 `dev-fast-40`，再用全量 `dev-240 retrieval-only` 验证 Candidate Recall：
   - document Recall@30 ≥75%；
   - chunk Recall@30 ≥60%、Recall@50 ≥70%。
5. 只有候选召回达标后才实现 G4/R2 的专用 reranker；必须保留 RRF fallback。
6. 精排后使用软文档多样性，不采用“每文档绝对最多 2 chunk”。
7. 最终执行 `dev-e2e-30 → dev-240 → val-80 → frozen-80`，Prompt 在整个 Step R 保持 `chat:v4`。

## 重要取舍

- 精排优先使用 Cross-encoder / 专用 reranker，不用聊天模型逐条评分。
- 精排无法找回候选集外的证据；Candidate Recall 未达标时必须继续修召回。
- 当前约 31 篇、约 441 chunk，继续使用 PostgreSQL + pgvector；不引入独立向量数据库。
- 玩家评论不进入知识向量库；趋势和告警继续走受控数据 Tool。
- 线上 Agent 仍只读；所有查询保持 Organization + Workspace + PUBLISHED + effective window 过滤。
- 检索行为变化记录为 `knowledge:rrf:v2`；R0 仅评测能力变化，不应提前修改线上检索版本。

## 当前关键文件

- 执行方案：`docs/agent-optimization-todo.md`
- CLI：`scripts/run-rag-gold-evaluation.ps1`
- 金标 Runner：`src/main/java/com/insightflow/evaluation/rag/RagGoldManualEvaluationRunner.java`
- 单题执行：`src/main/java/com/insightflow/evaluation/rag/RagEvaluationCaseExecutor.java`
- 聚合评分：`src/main/java/com/insightflow/evaluation/rag/RagGoldManualEvaluationScorer.java`
- 检索入口：`src/main/java/com/insightflow/knowledge/KnowledgeSearchTool.java`
- RRF SQL：`src/main/java/com/insightflow/knowledge/JdbcKnowledgeVectorStore.java`
- 类型计划：`src/main/java/com/insightflow/knowledge/KnowledgeRetrievalPlanner.java`
- 证据护栏：`src/main/java/com/insightflow/knowledge/KnowledgeEvidenceGuardrail.java`
- dev 金标：`evaluation/rag/gold/seeds/ops-rag-v1-dev-240.json`

## 当前验证事实

最近一次 Step M 定向测试通过：

```powershell
.\mvnw.cmd test -q "-Dtest=RagGoldAssertionMatcherTest,RagGoldManualEvaluationScorerTest,RagGoldManualEvaluationCarryForwardSupportTest,RagGoldEvidenceMatcherTest,RagGoldManualEvaluationRunnerTest"
```

未执行全量 Maven 测试；未运行新代码下的完整 dev-240。不要声称 Step M 的线上指标已验证。

## 不可突破的边界

- 不提交、推送、重置或删除 Git 内容，除非用户明确要求。
- 不把 API Key、JWT、密码或原始业务数据写入代码、日志或文档。
- 不保存模型原始思维链；评测缓存只保存必要的 query hash、候选公开 ID、rank、分数和版本元数据。
- FROZEN split 不展示逐题金标细节；检索优化只能在 dev/val 调参。
