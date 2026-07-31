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
}
