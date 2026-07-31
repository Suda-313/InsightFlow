package com.insightflow.knowledge;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从问题文本抽取运营事件编号等强标识符（如 KI-1301、STAB-202607）。
 *
 * <p>与 {@link KnowledgeQueryExpander} 共用同一正则，供 FTS 扩展与 P2 候选补召回/加权。</p>
 */
final class KnowledgeIdentifierExtractor {

    /** 字母前缀 + 连字符 + 数字，如 KI-1405。 */
    static final Pattern EVENT_ID = Pattern.compile("\\b([A-Z]{2,6}-\\d{3,6})\\b");

    /** 紧凑写法 KI1405 → 规范化为 KI-1405。 */
    private static final Pattern EVENT_ID_COMPACT = Pattern.compile("\\b([A-Z]{2,6})(\\d{3,6})\\b");

    private KnowledgeIdentifierExtractor() {
    }

    /** 返回大写、去重、保序的事件编号集合；无命中时为空集。 */
    static Set<String> extractEventIds(String text) {
        Set<String> ids = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return ids;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        Matcher matcher = EVENT_ID.matcher(upper);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        Matcher compact = EVENT_ID_COMPACT.matcher(upper);
        while (compact.find()) {
            ids.add(compact.group(1) + "-" + compact.group(2));
        }
        return ids;
    }

    /** 候选正文/标题/章节是否包含完整标识符 token（大小写不敏感）。 */
    static boolean containsExact(String haystack, String eventId) {
        if (haystack == null || eventId == null || eventId.isBlank()) {
            return false;
        }
        return haystack.toUpperCase(Locale.ROOT).contains(eventId.toUpperCase(Locale.ROOT));
    }
}
