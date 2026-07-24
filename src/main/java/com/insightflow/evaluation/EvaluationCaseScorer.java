package com.insightflow.evaluation;

import java.util.Locale;

/** P1 金标集的确定性评分器，P2 增加证据引用格式覆盖率以观察 Prompt 回归。 */
public class EvaluationCaseScorer {

    /** 只匹配题目维护的精确事实与禁止断言，避免第二个模型引入额外随机性。 */
    public EvaluationCaseScore score(GoldEvaluationCase evaluationCase, String answer) {
        String normalizedAnswer = normalize(answer);
        int coveredFacts = (int) evaluationCase.requiredFacts().stream()
                .map(this::normalize)
                .filter(normalizedAnswer::contains)
                .count();
        int hitForbiddenClaims = (int) evaluationCase.forbiddenClaims().stream()
                .map(this::normalize)
                .filter(normalizedAnswer::contains)
                .count();
        boolean refusalCompliant = !evaluationCase.refusalExpected()
                || (coveredFacts > 0 && hitForbiddenClaims == 0);
        boolean answerSpecific = coveredFacts > 0;
        boolean evidenceCitationPresent = answer != null && answer.contains("[证据:");
        return new EvaluationCaseScore(
                evaluationCase.requiredFacts().size(),
                coveredFacts,
                evaluationCase.forbiddenClaims().size(),
                hitForbiddenClaims,
                refusalCompliant,
                answerSpecific,
                evidenceCitationPresent);
    }

    /** 仅消除大小写、空白和常见标点差异，不做模糊语义匹配。 */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。；、：！？,.!?;:]", "");
    }
}
