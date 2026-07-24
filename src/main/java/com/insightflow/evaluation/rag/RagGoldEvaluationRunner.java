package com.insightflow.evaluation.rag;

import java.util.List;
import java.util.Set;

/**
 * 以固定证据规则计算 RAG 金标指标的运行器。
 *
 * <p>该类只负责评分，不负责生成答案或选择数据库查询；执行器注入观测值后，
 * 可以在真实 Agentic RAG、集成测试和离线回放之间复用相同口径。</p>
 */
public class RagGoldEvaluationRunner {

    /**
     * 逐题执行并汇总三个指标。
     * 没有预期证据或没有知识性引用的题目不会污染对应分母，避免把“应回答知识缺口”的题误判为低引用率。
     */
    public RagEvaluationMetrics run(List<RagGoldEvaluationCase> cases, RagGoldEvaluationExecutor executor) {
        int expectedEvidenceCount = 0;
        int retrievedExpectedEvidenceCount = 0;
        int citedEvidenceCount = 0;
        int correctCitationCount = 0;
        int ungroundedAnswers = 0;

        for (RagGoldEvaluationCase evaluationCase : cases) {
            RagEvaluationObservation observation = executor.execute(evaluationCase);
            Set<String> expected = evaluationCase.expectedEvidenceIds();
            expectedEvidenceCount += expected.size();
            retrievedExpectedEvidenceCount += countExpectedEvidenceMatches(expected, observation.retrievedEvidenceIds());
            citedEvidenceCount += observation.citedEvidenceIds().size();
            correctCitationCount += countIntersection(observation.citedEvidenceIds(), observation.retrievedEvidenceIds());
            if (observation.containsKnowledgeClaim()
                    && (observation.citedEvidenceIds().isEmpty()
                    || countIntersection(observation.citedEvidenceIds(), observation.retrievedEvidenceIds())
                    != observation.citedEvidenceIds().size())) {
                ungroundedAnswers++;
            }
        }

        return new RagEvaluationMetrics(
                ratio(retrievedExpectedEvidenceCount, expectedEvidenceCount),
                ratio(correctCitationCount, citedEvidenceCount),
                ratio(ungroundedAnswers, cases.size()),
                cases.size());
    }

    /** 只对集合交集计数，重复引文不能虚增召回或引用正确性。 */
    private int countIntersection(Set<String> left, Set<String> right) {
        return (int) left.stream().filter(right::contains).count();
    }

    /**
     * 金标以稳定的文档证据前缀描述“应当召回哪篇知识”，实际运行结果则带有版本和切片号。
     * 每个期望前缀最多计一次，避免同一篇文档的多个切片虚增召回率。
     */
    private int countExpectedEvidenceMatches(Set<String> expectedPrefixes, Set<String> retrievedEvidenceIds) {
        return (int) expectedPrefixes.stream()
                .filter(prefix -> retrievedEvidenceIds.stream().anyMatch(id -> id.startsWith(prefix)))
                .count();
    }

    /** 分母为零时按零处理，避免空金标集产生 NaN 并污染历史指标。 */
    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
