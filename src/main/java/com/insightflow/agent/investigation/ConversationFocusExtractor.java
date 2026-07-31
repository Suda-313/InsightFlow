package com.insightflow.agent.investigation;

import com.insightflow.knowledge.KnowledgeQueryExpander;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 从调查证据或前序对话文本抽取会话焦点槽位。
 *
 * <p>焦点只来自确定性 Tool 结果与用户原文中的结构化 token，不写入模型输出。
 * 抽不到任何槽位时返回空焦点，调用方不得覆盖会话已有值。</p>
 */
@Component
public class ConversationFocusExtractor {

    /** 趋势证据 id：trend:{canonicalKey}:{timeWindow} */
    private static final Pattern TREND_EVIDENCE_ID =
            Pattern.compile("^trend:([^:]+):(.+)$");

    /** 从趋势证据正文还原用户可读主题名：；{name} 最近N天 */
    private static final Pattern TREND_TOPIC_FROM_CONTENT =
            Pattern.compile("；(.+?)\\s+最近\\d+天");

    /** 分布证据 Top 列表首项：{name} {count} 条 */
    private static final Pattern DISTRIBUTION_TOP_TOPIC =
            Pattern.compile("Top\\d+：([^；]+?)\\s+\\d+\\s+条");

    private static final Pattern TIME_WINDOW_NEAR = Pattern.compile("近\\d+天");

    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})[-/年](\\d{1,2})");

    /** 去掉尾部疑问式后缀，保留可作检索主体的短语。 */
    private static final Pattern TRAILING_QUESTION =
            Pattern.compile("(是什么|有哪些|怎么.*|为什么.*|几点.*|多少.*|吗|呢)[?？]?$");

    /** 多轮前序 user 句常见套话后缀；剥离后 topicKey 更短、更利于指代替换。 */
    private static final Pattern CONTEXT_FILLER_SUFFIX =
            Pattern.compile("(的相关说明是什么|的相关说明|的说明是什么|的说明)[?？]?$");

    /** 焦点 topicKey 最大长度，防止整句问话前缀污染检索 query。 */
    private static final int MAX_TOPIC_KEY_LENGTH = 48;

    private final KnowledgeQueryExpander queryExpander;

    public ConversationFocusExtractor(KnowledgeQueryExpander queryExpander) {
        this.queryExpander = queryExpander;
    }

    /**
     * 从本轮调查结果与用户消息合并焦点；用户消息中的版本号可补充证据未覆盖的槽位。
     */
    public ChatSessionFocus extract(InvestigationResult result, String userMessage) {
        ChatSessionFocus fromEvidence = extractFromEvidence(result);
        ChatSessionFocus fromMessage = extractVersionFromText(userMessage);
        return fromEvidence.merge(fromMessage);
    }

    /**
     * 评测专用：从前序 context turns 的 user 消息文本抽焦点，无 InvestigationResult 时使用。
     */
    public ChatSessionFocus extractFromText(List<ContextTurn> contextTurns) {
        if (contextTurns == null || contextTurns.isEmpty()) {
            return ChatSessionFocus.empty();
        }
        String lastUser = lastUserContent(contextTurns);
        if (lastUser.isBlank()) {
            return ChatSessionFocus.empty();
        }
        String versionLabel = queryExpander.extractVersionLabel(lastUser);
        String topicKey = extractTopicFromUserMessage(lastUser, versionLabel);
        String timeWindow = extractTimeWindowFromText(lastUser);
        return new ChatSessionFocus(topicKey, timeWindow, versionLabel);
    }

    private ChatSessionFocus extractFromEvidence(InvestigationResult result) {
        if (result == null || result.evidence().isEmpty()) {
            return ChatSessionFocus.empty();
        }
        String topicKey = null;
        String timeWindow = null;
        for (InvestigationEvidence item : result.evidence()) {
            if (item.tool() == InvestigationToolType.ISSUE_TREND) {
                Optional<ChatSessionFocus> trend = parseTrendEvidence(item);
                if (trend.isPresent()) {
                    ChatSessionFocus focus = trend.get();
                    topicKey = firstNonBlank(focus.topicKey(), topicKey);
                    timeWindow = firstNonBlank(focus.timeWindow(), timeWindow);
                }
            } else if (item.tool() == InvestigationToolType.TOPIC_DISTRIBUTION && topicKey == null) {
                topicKey = parseDistributionTopic(item.content());
            }
        }
        return new ChatSessionFocus(topicKey, timeWindow, null);
    }

    private Optional<ChatSessionFocus> parseTrendEvidence(InvestigationEvidence item) {
        if ("trend:unresolved".equals(item.id())) {
            return Optional.empty();
        }
        Matcher idMatcher = TREND_EVIDENCE_ID.matcher(item.id());
        if (!idMatcher.matches()) {
            return Optional.empty();
        }
        String encodedWindow = idMatcher.group(2);
        String topicFromContent = parseTrendTopicFromContent(item.content());
        return Optional.of(new ChatSessionFocus(
                topicFromContent,
                humanizeTimeWindow(encodedWindow),
                null));
    }

    private String parseTrendTopicFromContent(String content) {
        Matcher matcher = TREND_TOPIC_FROM_CONTENT.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String parseDistributionTopic(String content) {
        Matcher matcher = DISTRIBUTION_TOP_TOPIC.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        String firstEntry = matcher.group(1).trim();
        int space = firstEntry.indexOf(' ');
        return space > 0 ? firstEntry.substring(0, space) : firstEntry;
    }

    private ChatSessionFocus extractVersionFromText(String userMessage) {
        String version = queryExpander.extractVersionLabel(userMessage);
        if (version == null) {
            return ChatSessionFocus.empty();
        }
        return new ChatSessionFocus(null, null, version);
    }

    private String extractTopicFromUserMessage(String userMessage, String versionLabel) {
        String normalized = userMessage.trim();
        if (normalized.startsWith("玩家问")) {
            normalized = normalized.substring(3).trim();
        }
        normalized = CONTEXT_FILLER_SUFFIX.matcher(normalized).replaceFirst("").trim();
        if (versionLabel != null) {
            normalized = normalized.replace("v" + versionLabel, " ")
                    .replace(versionLabel, " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
        normalized = TRAILING_QUESTION.matcher(normalized).replaceFirst("").trim();
        normalized = normalized.replaceAll("是$", "").trim();
        if (normalized.isBlank()) {
            return versionLabel;
        }
        if (normalized.length() > MAX_TOPIC_KEY_LENGTH) {
            normalized = normalized.substring(0, MAX_TOPIC_KEY_LENGTH).trim();
        }
        return normalized;
    }

    private String extractTimeWindowFromText(String text) {
        Matcher near = TIME_WINDOW_NEAR.matcher(text);
        if (near.find()) {
            return near.group();
        }
        Matcher yearMonth = YEAR_MONTH.matcher(text);
        if (yearMonth.find()) {
            return yearMonth.group(1) + "年" + yearMonth.group(2) + "月";
        }
        return null;
    }

    private String lastUserContent(List<ContextTurn> contextTurns) {
        String last = "";
        for (ContextTurn turn : contextTurns) {
            if (turn != null && "user".equalsIgnoreCase(turn.role()) && turn.content() != null) {
                last = turn.content().trim();
            }
        }
        return last;
    }

    private String humanizeTimeWindow(String encoded) {
        return switch (encoded) {
            case "last_14_days" -> "近14天";
            case "last_7_days" -> "近7天";
            default -> encoded.replace('_', ' ');
        };
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}
