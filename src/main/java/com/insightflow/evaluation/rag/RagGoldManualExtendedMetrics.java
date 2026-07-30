package com.insightflow.evaluation.rag;

import java.util.List;
import java.util.Map;

/**
 * 人工金标 RAG 评测的扩展聚合指标。
 *
 * <p>与 {@link RagEvaluationMetrics} 的三项 legacy 指标并存，供 JSON 摘要与 frozen 门禁使用；
 * 不保存模型回答、断言原文或 chunk 正文。</p>
 */
public record RagGoldManualExtendedMetrics(
        /** 数据集业务键，如 ops-rag-v1。 */
        String datasetKey,
        /** 数据集版本标签，如 dev-240 / frozen-80。 */
        String datasetVersionLabel,
        /** DEVELOPMENT / VALIDATION / FROZEN。 */
        String split,
        /** 发布时计算的 SHA-256 checksum，用于复核批次。 */
        String checksum,
        /** 本批运行的 case_key 列表，顺序与执行一致。 */
        List<String> caseKeys,
        /** 文档级 Recall@1/@3/@8。 */
        double documentRecallAt1,
        double documentRecallAt3,
        double documentRecallAt8,
        /** Chunk/版本级 Recall@1/@3/@8。 */
        double chunkRecallAt1,
        double chunkRecallAt3,
        double chunkRecallAt8,
        /** 平均倒数排名（仅对有期望证据的题聚合）。 */
        double mrr,
        /** 前 8 位 nDCG（二元相关性）。 */
        double ndcgAt8,
        /** REQUIRED_FACT 断言覆盖率。 */
        double requiredFactCoverageRate,
        /** FORBIDDEN_CLAIM 命中率，越低越好。 */
        double forbiddenClaimHitRate,
        /** 引用 ⊆ 检索 的比例。 */
        double citationSupportRate,
        /** should_refuse 合规率；无拒答题时为 null。 */
        Double shouldRefuseComplianceRate,
        /** 检索阶段 P50/P95 毫秒。 */
        Long retrievalP50Ms,
        Long retrievalP95Ms,
        /** 生成阶段 P50/P95 毫秒。 */
        Long generationP50Ms,
        Long generationP95Ms,
        /** 参与 latency 分位计算的 succeeded 题数；无样本时为 0，P50/P95 为 null。 */
        int latencySampleCount,
        /** Token 累计；供应商未返回 Usage 时为 "unavailable"。 */
        String promptTokens,
        String completionTokens,
        String totalTokens,
        /** 按 question_type 的分项聚合。 */
        Map<String, RagGoldQuestionTypeMetrics> byQuestionType,
        /** 成功完成检索+生成的题数。 */
        int succeededCaseCount,
        /** 单题失败题数。 */
        int failedCaseCount,
        /** 受控 Prompt 版本。 */
        String promptVersion,
        /** 嵌入模型名称。 */
        String embeddingModel,
        /** 检索配置版本。 */
        String retrievalConfigVersion,
        /** retrieval-only 漏斗聚合；端到端批次为 null。 */
        RagGoldRetrievalFunnelAggregate retrievalFunnel,
        /** end-to-end 或 retrieval-only。 */
        String evaluationMode,
        /** 全部 requirement 组在 Top8 均满足的题占比（有期望 evidence 题为分母）。 */
        double finalEvidenceCoverageAt8,
        /** 与 finalEvidenceCoverageAt8 相同；CROSS/VERSION 的主检索指标别名。 */
        double requirementGroupCoverageAt8,
        /** 按题型主指标加权后的整体 Recall@8（SINGLE=chunk，CROSS/VERSION=requirement 组）。 */
        double primaryRecallAt8,
        /** CROSS 题在最终 Top8 双文档命中率。 */
        double finalCrossDocumentDualHitAt8,
        /** RRF Top8 未命中、精排 Top8 命中的题数。 */
        int rerankGainedCaseCount,
        /** RRF Top8 命中、精排 Top8 未命中的题数。 */
        int rerankLostCaseCount,
        /** 精排后相关证据名次下降或跌出 Top8 的题数。 */
        int rerankDemotedCaseCount,
        /** 启用精排时 fallback 到 RRF 的题占比。 */
        double rerankFallbackRate,
        Long rerankLatencyP50Ms,
        Long rerankLatencyP95Ms,
        /** 与 chunkRecallAt8 相同；保留 chunkRecallAt8 以兼容旧 JSON。 */
        double chunkRecallAt8AnyEvidence,
        /** chunk Recall@8 口径说明：SINGLE 用 any-evidence；CROSS/VERSION 主指标为 requirement 组覆盖。 */
        String chunkRecallMetricMode) {

    public RagGoldManualExtendedMetrics {
        caseKeys = List.copyOf(caseKeys);
        byQuestionType = Map.copyOf(byQuestionType);
    }
}
