package com.insightflow.service;

import com.insightflow.entity.ChatMessage;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 将 12 条短期窗口之外的更早消息压缩为确定性滚动摘要。
 *
 * <p>不调用 LLM：每条仅保留 user 问句与 assistant 结论一行，用分号拼接并截断上限，
 * 供 {@link ConversationHistoryCompactor} 注入 Prompt，避免极长会话 token 线性增长。</p>
 */
@Component
public class SessionRollingSummaryBuilder {

    /** 超出该条数时触发滚动摘要更新（与 recentMessagesForModel 窗口一致）。 */
    static final int RECENT_MESSAGE_WINDOW = 12;

    /** 滚动摘要总长度上限，单点可调。 */
    static final int ROLLING_SUMMARY_MAX_CHARS = 600;

    /** 摘要中单条 user/assistant 片段上限。 */
    private static final int LINE_MAX_CHARS = 80;

    private final ConversationHistoryCompactor historyCompactor;

    public SessionRollingSummaryBuilder(ConversationHistoryCompactor historyCompactor) {
        this.historyCompactor = historyCompactor;
    }

    /**
     * 从完整消息列表生成滚动摘要；消息不足窗口时返回 null 表示无需摘要段。
     *
     * @param allMessagesAsc 会话全部消息，按时间升序
     */
    public String buildFromAllMessages(List<ChatMessage> allMessagesAsc) {
        if (allMessagesAsc == null || allMessagesAsc.size() <= RECENT_MESSAGE_WINDOW) {
            return null;
        }
        int olderCount = allMessagesAsc.size() - RECENT_MESSAGE_WINDOW;
        List<ChatMessage> older = allMessagesAsc.subList(0, olderCount);
        StringBuilder summary = new StringBuilder();
        for (ChatMessage message : older) {
            appendSegment(summary, historyCompactor.compactLine(message));
        }
        return finalizeSummary(summary);
    }

    /**
     * 在已冻结的摘要后仅追加本次滑出短期窗口的消息。
     *
     * <p>该方法不读取仓储，也不推导时间范围；调用方通过摘要游标保证传入消息从未被消费过。
     * 追加后仍保留最早 600 字符，与全量构建的历史截断语义一致。</p>
     */
    public String appendIncrementally(String existingSummary, List<ChatMessage> newlyEvictedMessages) {
        StringBuilder summary = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            summary.append(existingSummary.trim());
        }
        if (newlyEvictedMessages != null) {
            for (ChatMessage message : newlyEvictedMessages) {
                appendSegment(summary, historyCompactor.compactLine(message));
            }
        }
        return finalizeSummary(summary);
    }

    private void appendSegment(StringBuilder summary, String segment) {
        if (segment == null || segment.isBlank()) {
            return;
        }
        String line = segment.trim();
        if (line.length() > LINE_MAX_CHARS) {
            line = line.substring(0, LINE_MAX_CHARS).trim() + "…";
        }
        if (!summary.isEmpty()) {
            summary.append("；");
        }
        summary.append(line);
    }

    /** 统一全量和增量路径的空值与总长度语义，避免两条摘要规则分叉。 */
    private String finalizeSummary(StringBuilder summary) {
        if (summary.isEmpty()) {
            return null;
        }
        String text = summary.toString().trim();
        if (text.length() <= ROLLING_SUMMARY_MAX_CHARS) {
            return text;
        }
        return text.substring(0, ROLLING_SUMMARY_MAX_CHARS).trim() + "…";
    }
}
