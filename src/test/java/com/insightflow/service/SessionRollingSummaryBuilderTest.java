package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 滚动摘要：窗口外消息压缩、上限截断与空列表行为。 */
class SessionRollingSummaryBuilderTest {

    private final SessionRollingSummaryBuilder builder =
            new SessionRollingSummaryBuilder(new ConversationHistoryCompactor());

    private static final Long WS = 1L;
    private static final Long SESSION = 2L;

    @Test
    void returnsNullWhenWithinRecentWindow() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < SessionRollingSummaryBuilder.RECENT_MESSAGE_WINDOW; i++) {
            messages.add(ChatMessage.user(WS, SESSION, "问" + i));
        }

        assertThat(builder.buildFromAllMessages(messages)).isNull();
    }

    @Test
    void compressesOlderMessagesBeyondWindow() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user(WS, SESSION, "最早的问题"));
        messages.add(ChatMessage.assistant(WS, SESSION, "## 结论\n最早结论。"));
        for (int i = 0; i < SessionRollingSummaryBuilder.RECENT_MESSAGE_WINDOW; i++) {
            messages.add(ChatMessage.user(WS, SESSION, "近期" + i));
        }

        String summary = builder.buildFromAllMessages(messages);

        assertThat(summary)
                .contains("user: 最早的问题")
                .contains("assistant: 最早结论。")
                .doesNotContain("近期");
    }

    @Test
    void truncatesSummaryAtMaxLength() {
        List<ChatMessage> messages = new ArrayList<>();
        // 窗口外需足够多条，使拼接后超过 ROLLING_SUMMARY_MAX_CHARS 才触发总截断
        for (int i = 0; i < SessionRollingSummaryBuilder.RECENT_MESSAGE_WINDOW + 18; i++) {
            messages.add(ChatMessage.user(WS, SESSION, "长".repeat(50) + i));
        }

        String summary = builder.buildFromAllMessages(messages);

        assertThat(summary).hasSizeLessThanOrEqualTo(SessionRollingSummaryBuilder.ROLLING_SUMMARY_MAX_CHARS + 1);
        assertThat(summary).endsWith("…");
    }

    /** 增量路径只能压缩本次滑出短期窗口的消息，不能再次遍历或重复拼接旧历史。 */
    @Test
    void appendsOnlyNewlyEvictedMessagesToExistingSummary() {
        ChatMessage newlyEvicted = ChatMessage.assistant(WS, SESSION, "## 结论\n新增结论");

        String summary = builder.appendIncrementally("user: 已处理的问题", List.of(newlyEvicted));

        assertThat(summary).isEqualTo("user: 已处理的问题；assistant: 新增结论");
    }

    /** 达到既有上限后仍保留最早的 600 字符，增量消息不改变兼容的截断语义。 */
    @Test
    void keepsEarliestSummaryPrefixWhenIncrementalAppendExceedsLimit() {
        String existing = "早".repeat(SessionRollingSummaryBuilder.ROLLING_SUMMARY_MAX_CHARS);

        String summary = builder.appendIncrementally(existing, List.of(ChatMessage.user(WS, SESSION, "后续消息")));

        assertThat(summary).isEqualTo(existing + "…");
    }
}
