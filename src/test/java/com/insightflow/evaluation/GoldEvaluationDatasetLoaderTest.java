package com.insightflow.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 金标集是后续 Prompt、模型和检索策略的共同质量基线，测试应防止资源文件被误删或退化为无约束题目。
 */
class GoldEvaluationDatasetLoaderTest {

    /**
     * 首版必须覆盖趋势、告警、比较、拒答和报告五类问题；每条用例均有可核对事实而非主观参考答案。
     */
    @Test
    void loadsThirtyUniqueCasesWithAllRequiredCategoriesAndScoringConstraints() {
        GoldEvaluationDataset dataset = new GoldEvaluationDatasetLoader(new ObjectMapper()).load();

        assertThat(dataset.version()).isEqualTo("gold:v1");
        assertThat(dataset.cases()).hasSize(30);
        assertThat(dataset.cases()).extracting(GoldEvaluationCase::category)
                .containsExactlyInAnyOrderElementsOf(List.of(
                        "trend", "trend", "trend", "trend", "trend", "trend",
                        "alert", "alert", "alert", "alert", "alert", "alert",
                        "comparison", "comparison", "comparison", "comparison", "comparison", "comparison",
                        "refusal", "refusal", "refusal", "refusal", "refusal", "refusal",
                        "report", "report", "report", "report", "report", "report"));
        assertThat(dataset.cases()).extracting(GoldEvaluationCase::caseId).doesNotHaveDuplicates();
        assertThat(dataset.cases()).allSatisfy(evaluationCase -> {
            assertThat(evaluationCase.fixtureId()).isEqualTo("game-support:v1");
            assertThat(evaluationCase.question()).isNotBlank();
            assertThat(evaluationCase.requiredFacts()).isNotEmpty();
            assertThat(evaluationCase.forbiddenClaims()).isNotEmpty();
            assertThat(evaluationCase.forbiddenClaims())
                    .noneMatch(claim -> claim.startsWith("不得") || claim.startsWith("不应"));
        });
    }
}
