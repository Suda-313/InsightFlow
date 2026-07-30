package com.insightflow.evaluation.rag;

/**
 * {@link RagEvaluationFixtureFactory} 的用途边界标记。
 *
 * <p>生产质量门禁应使用 {@link RagGoldManualEvaluationRunner} 加载已发布人工金标；
 * 动态 Fixture 仅用于测试回归与语料健康检查。</p>
 */
public enum RagEvaluationFixturePurpose {

    /** 单元/集成测试用的最小动态题集。 */
    TEST_FIXTURE,

    /** 检查当前 Workspace 可见语料是否足以生成链路回归题。 */
    CORPUS_HEALTH_CHECK
}
