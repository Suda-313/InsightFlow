package com.insightflow.service;

import com.insightflow.entity.ChatMessage;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 将会话历史压缩为注入模型的低 token 摘要。
 *
 * <p>用户消息保留原文但截断上限；助手消息只保留五段式回答中的 {@code ## 结论} 正文，避免证据、推测和建议动作
 * 重复占用上下文。输出结构与 {@link ChatService} 原先的历史块一致，便于 Prompt 版本升级而不改变段落布局。</p>
 */
@Component
public class ConversationHistoryCompactor {

    /** 用户消息上限：足够保留问题上下文，又低于旧版 1000 字以减少 token。 */
    private static final int USER_MAX_CHARS = 500;

    /** 助手结论段上限：只注入决策摘要，不重复完整五段式。 */
    private static final int ASSISTANT_MAX_CHARS = 300;

    /** 五段式回答中唯一需要进入历史的二级标题。 */
    private static final String CONCLUSION_HEADING = "## 结论";

    /** 空历史占位，与线上一致以便模型识别“无 prior turn”。 */
    private static final String EMPTY_HISTORY = "\n## 最近对话\n暂无历史对话。\n";

    /**
     * 将最近消息格式化为 Prompt 历史块；调用方负责条数上限（如 twelve turns）。
     *
     * @param rollingSummary 可选的更早对话摘要，为 null 时不注入
     */
    public String format(List<ChatMessage> history, String rollingSummary) {
        if (history.isEmpty() && (rollingSummary == null || rollingSummary.isBlank())) {
            return EMPTY_HISTORY;
        }
        StringBuilder formatted = new StringBuilder();
        if (rollingSummary != null && !rollingSummary.isBlank()) {
            formatted.append("\n## 更早对话摘要\n")
                    .append(rollingSummary.trim())
                    .append('\n');
        }
        if (history.isEmpty()) {
            formatted.append("\n## 最近对话\n暂无历史对话。\n");
            return formatted.toString();
        }
        formatted.append("\n## 最近对话\n");
        history.forEach(message -> formatted.append(message.getRole())
                .append(": ")
                .append(compactContent(message))
                .append('\n'));
        return formatted.toString();
    }

    /** 兼容旧调用：无滚动摘要。 */
    public String format(List<ChatMessage> history) {
        return format(history, null);
    }

    /**
     * 供滚动摘要使用的单行压缩；格式为 {@code role: 正文}，与历史块内单条一致。
     */
    public String compactLine(ChatMessage message) {
        return message.getRole() + ": " + compactContent(message);
    }

    /** 按角色选择压缩策略：用户全文截断，助手只取结论段。 */
    private String compactContent(ChatMessage message) {
        String raw = message.getContent();
        if ("assistant".equals(message.getRole())) {
            return truncate(extractConclusionBody(raw), ASSISTANT_MAX_CHARS);
        }
        return truncate(raw, USER_MAX_CHARS);
    }

    /**
     * 从助手五段式回答中抽取 {@code ## 结论} 与下一 {@code ## } 标题之间的正文。
     * 早期或非标准回答没有该标题时退化为全文，由上层截断兜底。
     */
    private String extractConclusionBody(String content) {
        int headingIndex = content.indexOf(CONCLUSION_HEADING);
        if (headingIndex < 0) {
            return content;
        }
        int bodyStart = headingIndex + CONCLUSION_HEADING.length();
        while (bodyStart < content.length() && Character.isWhitespace(content.charAt(bodyStart))) {
            bodyStart++;
        }
        int bodyEnd = content.length();
        int nextSection = content.indexOf("\n## ", bodyStart);
        if (nextSection >= 0) {
            bodyEnd = nextSection;
        }
        return content.substring(bodyStart, bodyEnd).trim();
    }

    /** 超长文本统一追加省略号，与 ChatService 历史格式保持一致。 */
    private String truncate(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "…";
    }
}
