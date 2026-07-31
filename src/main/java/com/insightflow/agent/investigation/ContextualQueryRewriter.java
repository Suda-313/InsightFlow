package com.insightflow.agent.investigation;

import com.insightflow.knowledge.KnowledgeQueryExpander;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 多轮指代补全：当前问句缺主体时，用会话焦点补出一个自足查询。
 *
 * <p>只做确定性字符串拼接，不调用模型：改写结果要同时驱动意图路由和向量检索，
 * 必须可重放，否则金标批次之间无法比较。</p>
 */
@Component
public class ContextualQueryRewriter {

    private static final Pattern TIME_WINDOW = Pattern.compile("近\\d+天|last_\\d+_days");

    private static final Pattern DATE_FRAGMENT = Pattern.compile("(\\d{4})[-/年](\\d{1,2})");

    /** 指代词表：命中且缺主体时才触发改写。 */
    private static final List<String> REFERENCE_PRONOUNS = List.of(
            "它", "这个", "那个", "这些", "上面", "刚才", "里面", "其中");

    /** 改写结果长度上限，避免 prefix+整句前序问话拖垮向量检索。 */
    private static final int MAX_REWRITTEN_LENGTH = 120;

    /** 用于在 follow-up 中替换首个指代词的锚点 token（按长度降序，避免「这个」误切「这个版本」）。 */
    private static final List<String> SUBSTITUTABLE_REFERENCES = List.of(
            "里面", "其中", "上面", "刚才", "这些", "这个", "那个", "它");

    /** 延续指令：用户明确要求接着上一轮讨论。 */
    private static final List<String> CONTINUATION_COMMANDS = List.of("继续", "再看看", "展开说说");

    /** 省略式追问的起止标记；配合长度上限判定。 */
    private static final List<String> ELLIPTICAL_MARKERS = List.of("为什么", "怎么", "多少", "呢", "还有");

    /**
     * 可独立定位的主体线索：命中任一则视为自足问句，不得改写以免污染单轮金标。
     */
    private static final List<String> TOPIC_KEYWORDS = List.of(
            "版本", "更新", "公告", "登录", "结算", "背包", "奖励", "维护", "工单", "玩家",
            "异常", "故障", "bug", "舆情", "活动", "停服", "渠道", "复盘", "事故", "客服",
            "sop", "release", "玩法", "反馈");

    private final KnowledgeQueryExpander queryExpander;

    public ContextualQueryRewriter(KnowledgeQueryExpander queryExpander) {
        this.queryExpander = queryExpander;
    }

    /**
     * 按三条件决定是否改写；不触发时 {@code rewritten} 与 {@code original} 引用相同。
     */
    public RewriteOutcome rewrite(String message, ChatSessionFocus focus) {
        String original = message == null ? "" : message;
        if (focus == null || focus.isEmpty()) {
            return notTriggered(original, "focus_empty");
        }
        if (hasSelfContainedSubject(original)) {
            return notTriggered(original, "self_contained");
        }
        if (!shouldTriggerRewrite(original)) {
            return notTriggered(original, "no_trigger");
        }
        String rewritten = applyTemplate(original, focus);
        return new RewriteOutcome(original, rewritten, true, "contextual_reference");
    }

    private RewriteOutcome notTriggered(String original, String reason) {
        return new RewriteOutcome(original, original, false, reason);
    }

    /** 消息已含版本、日期或业务主题词，可独立路由与检索。 */
    private boolean hasSelfContainedSubject(String message) {
        if (message.isBlank()) {
            return false;
        }
        if (queryExpander.extractVersionLabel(message) != null) {
            return true;
        }
        if (TIME_WINDOW.matcher(message).find() || DATE_FRAGMENT.matcher(message).find()) {
            return true;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        for (String keyword : TOPIC_KEYWORDS) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldTriggerRewrite(String message) {
        if (containsAny(message, REFERENCE_PRONOUNS)) {
            return true;
        }
        if (containsAny(message, CONTINUATION_COMMANDS)) {
            return true;
        }
        if (message.length() <= 15) {
            for (String marker : ELLIPTICAL_MARKERS) {
                if (message.startsWith(marker) || message.endsWith(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String applyTemplate(String original, ChatSessionFocus focus) {
        String anchor = buildAnchor(focus);
        if (anchor.isBlank()) {
            return original;
        }
        if (containsAny(original, REFERENCE_PRONOUNS)) {
            return capLength(substituteFirstReference(original, anchor));
        }
        return capLength(anchor + " " + original);
    }

    /** 版本号优先，便于与 embed 前缀中的 vN 对齐。 */
    private String buildAnchor(ChatSessionFocus focus) {
        StringBuilder anchor = new StringBuilder();
        if (focus.versionLabel() != null && !focus.versionLabel().isBlank()) {
            anchor.append(focus.versionLabel().trim());
        }
        if (focus.topicKey() != null && !focus.topicKey().isBlank()) {
            if (!anchor.isEmpty()) {
                anchor.append(' ');
            }
            anchor.append(focus.topicKey().trim());
        }
        if (focus.timeWindow() != null && !focus.timeWindow().isBlank()) {
            if (!anchor.isEmpty()) {
                anchor.append(' ');
            }
            anchor.append(focus.timeWindow().trim());
        }
        return anchor.toString().trim();
    }

    private String substituteFirstReference(String original, String anchor) {
        for (String token : SUBSTITUTABLE_REFERENCES) {
            int index = original.indexOf(token);
            if (index >= 0) {
                return original.substring(0, index) + anchor + original.substring(index + token.length());
            }
        }
        return anchor + " " + original;
    }

    private String capLength(String rewritten) {
        if (rewritten.length() <= MAX_REWRITTEN_LENGTH) {
            return rewritten;
        }
        return rewritten.substring(0, MAX_REWRITTEN_LENGTH).trim();
    }

    private boolean containsAny(String message, List<String> tokens) {
        for (String token : tokens) {
            if (message.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
