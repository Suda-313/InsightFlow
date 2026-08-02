package com.insightflow.knowledge;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 确定性查询扩展：只用于词法 FTS，不改变 embedding 输入。
 *
 * <p>抽取版本号、事件编号、日期片段与业务类型提示词，提高 {@code websearch_to_tsquery}
 * 对运营语料的命中率；不接受模型生成的扩展词。</p>
 */
@Component
public class KnowledgeQueryExpander {

    /** 与 FTS 扩展共用，供会话焦点与 query 改写复用，避免多处维护版本号正则。 */
    static final Pattern VERSION = Pattern.compile("(?i)\\bv?(\\d+(?:\\.\\d+)+)\\b");
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})[-/年](\\d{1,2})");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})月");

    /**
     * 从文本中抽取首个版本号标签（如 {@code 1.4}）；无命中时返回 {@code null}。
     *
     * <p>供 {@link com.insightflow.agent.investigation.ConversationFocusExtractor} 与
     * {@link com.insightflow.agent.investigation.ContextualQueryRewriter} 判定问句是否已自足。</p>
     */
    public String extractVersionLabel(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = VERSION.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 在原问题后追加抽取到的结构化 token；无命中时返回原问题。
     */
    public String expand(String question) {
        if (question == null || question.isBlank()) {
            return question == null ? "" : question;
        }
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add(question.trim());

        Matcher versionMatcher = VERSION.matcher(question);
        while (versionMatcher.find()) {
            String numeric = versionMatcher.group(1);
            tokens.add(numeric);
            tokens.add("v" + numeric);
        }

        for (String id : KnowledgeIdentifierExtractor.extractEventIds(question)) {
            tokens.add(id);
            tokens.add(id.replace("-", ""));
        }

        Matcher yearMonthMatcher = YEAR_MONTH.matcher(question);
        while (yearMonthMatcher.find()) {
            tokens.add(yearMonthMatcher.group(1) + "-" + padMonth(yearMonthMatcher.group(2)));
            tokens.add(yearMonthMatcher.group(2) + "月");
        }

        Matcher monthMatcher = MONTH_DAY.matcher(question);
        while (monthMatcher.find()) {
            tokens.add(monthMatcher.group(1) + "月");
        }

        appendTypeHints(question, tokens);
        return String.join(" ", tokens);
    }

    /** 与 {@link KnowledgeRetrievalPlanner} 对齐的类型提示，只进入 FTS 不参与 SQL 过滤。 */
    private void appendTypeHints(String question, Set<String> tokens) {
        String normalized = question.toLowerCase(Locale.ROOT);
        addHint(normalized, tokens, "版本", "公告", "更新", "发布", "release");
        addHint(normalized, tokens, "已知问题", "bug", "异常", "故障");
        addHint(normalized, tokens, "客服", "工单", "sop", "处理流程", "流程");
        addHint(normalized, tokens, "舆情", "回应", "危机");
        addHint(normalized, tokens, "运营事件", "活动", "维护", "停服", "渠道");
        addHint(normalized, tokens, "复盘", "事后", "根因", "事故", "postmortem");
    }

    private void addHint(String normalized, Set<String> tokens, String... keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                tokens.add(keyword);
            }
        }
    }

    private String padMonth(String month) {
        return month.length() == 1 ? "0" + month : month;
    }
}
