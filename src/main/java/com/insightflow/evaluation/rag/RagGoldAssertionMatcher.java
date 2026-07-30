package com.insightflow.evaluation.rag;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 金标断言与模型回答的确定性匹配器。
 *
 * <p>短断言（归一化后 &lt;12 字）仍用子串包含，兼容 KI 编号、版本号等短事实；
 * 长断言改 token 交集，避免模型 paraphrase 导致事实覆盖率被系统性低估。</p>
 */
final class RagGoldAssertionMatcher {

    /** 归一化后低于此长度视为短断言，沿用子串匹配。 */
    static final int LONG_ASSERTION_THRESHOLD = 12;

    /** 长断言 token 命中率下限（含 bigram 与字母数字 token）。 */
    static final double TOKEN_HIT_RATIO = 0.6;

    private static final Pattern ALNUM_TOKEN = Pattern.compile("[a-z0-9]{2,}");

    /**
     * 判断归一化后的回答是否覆盖金标断言。
     *
     * @param normalizedAnswer 已 {@link #normalize} 的回答
     * @param assertionText 原始断言文本（金标）
     */
    boolean matches(String normalizedAnswer, String assertionText) {
        String normalizedAssertion = normalize(assertionText);
        if (normalizedAssertion.isEmpty() || normalizedAnswer.isEmpty()) {
            return false;
        }
        if (normalizedAssertion.length() < LONG_ASSERTION_THRESHOLD) {
            return normalizedAnswer.contains(normalizedAssertion);
        }
        if (normalizedAnswer.contains(normalizedAssertion)) {
            return true;
        }
        Set<String> assertionTokens = tokens(normalizedAssertion);
        if (!assertionTokens.isEmpty()) {
            Set<String> answerTokens = tokens(normalizedAnswer);
            long hits = assertionTokens.stream().filter(answerTokens::contains).count();
            if ((double) hits / assertionTokens.size() >= TOKEN_HIT_RATIO) {
                return true;
            }
        }
        return cjkCharCoverage(normalizedAnswer, normalizedAssertion) >= TOKEN_HIT_RATIO;
    }

    /** 断言中 distinct CJK 字在回答中出现的比例；补充 paraphrase 下 bigram 不足的场景。 */
    private double cjkCharCoverage(String normalizedAnswer, String normalizedAssertion) {
        Set<Character> assertionChars = new LinkedHashSet<>();
        for (int i = 0; i < normalizedAssertion.length(); i++) {
            char ch = normalizedAssertion.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                assertionChars.add(ch);
            }
        }
        if (assertionChars.isEmpty()) {
            return 0.0;
        }
        long hits = assertionChars.stream().filter(ch -> normalizedAnswer.indexOf(ch) >= 0).count();
        return (double) hits / assertionChars.size();
    }

    /** 与 {@link RagGoldManualEvaluationScorer#normalize} 相同规则，保证评分一致。 */
    String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replaceAll("[\\s，。；、：！？,.!?;:]", "");
    }

    /**
     * 从归一化文本提取匹配 token：字母数字串（≥2）+ 中文二字 bigram。
     * 不引入外部分词库，保持评测可复现。
     */
    Set<String> tokens(String normalized) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = ALNUM_TOKEN.matcher(normalized);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                cjk.append(ch);
            }
        }
        String cjkText = cjk.toString();
        if (cjkText.length() == 1) {
            result.add(cjkText);
        } else {
            for (int i = 0; i < cjkText.length() - 1; i++) {
                result.add(cjkText.substring(i, i + 2));
            }
        }
        return result;
    }
}
