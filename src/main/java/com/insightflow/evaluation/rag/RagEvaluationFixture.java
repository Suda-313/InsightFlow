package com.insightflow.evaluation.rag;

import java.util.List;

/**
 * 某次 RAG 评测运行使用的受控题集快照。
 *
 * <p>数据集版本由当前可见的已发布文档与版本 UUID 计算得出；同版本的批次才可作趋势比较，
 * 防止知识库内容已变化时仍把指标差异错误归因于 Prompt 或模型。</p>
 */
public record RagEvaluationFixture(
        /** 不暴露企业原文的可比较数据集版本。 */
        String datasetVersion,
        /** 运行前已经确定的评测题目集合。 */
        List<RagEvaluationCaseDefinition> cases) {

    /** 固化题目顺序，以便历史 JSON 与页面展示稳定。 */
    public RagEvaluationFixture {
        cases = List.copyOf(cases);
    }
}
