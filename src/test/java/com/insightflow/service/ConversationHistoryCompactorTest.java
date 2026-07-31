package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightflow.entity.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 历史压缩单元测试：验证助手五段式只留结论、无标题退化截断、用户 500 字上限与空历史占位。
 */
class ConversationHistoryCompactorTest {

    private final ConversationHistoryCompactor compactor = new ConversationHistoryCompactor();

    private static final Long WORKSPACE_ID = 1L;
    private static final Long SESSION_ID = 2L;

    /** 五段式助手回答进入 Prompt 时不得携带证据、推测或建议动作段。 */
    @Test
    void keepsOnlyConclusionSectionFromFivePartAssistantMessage() {
        String assistantBody = """
                ## 结论
                玩法 Bug 与版本更新相关。
                ## 证据
                [证据: trend:gameplay]
                ## 推测
                可能是入口改动导致。
                ## 未知项
                缺少活动数据。
                ## 建议动作
                排查版本差异。
                """;
        ChatMessage assistant = ChatMessage.assistant(WORKSPACE_ID, SESSION_ID, assistantBody);

        String formatted = compactor.format(List.of(assistant));

        assertThat(formatted)
                .isEqualTo("\n## 最近对话\nassistant: 玩法 Bug 与版本更新相关。\n")
                .doesNotContain("## 证据")
                .doesNotContain("## 建议动作")
                .doesNotContain("排查版本差异");
    }

    /** 早期或非标准助手消息没有 ## 结论 时，退化为全文并按 300 字截断。 */
    @Test
    void truncatesAssistantWithoutConclusionHeading() {
        String longPlain = "x".repeat(400);
        ChatMessage assistant = ChatMessage.assistant(WORKSPACE_ID, SESSION_ID, longPlain);

        String formatted = compactor.format(List.of(assistant));

        assertThat(formatted)
                .isEqualTo("\n## 最近对话\nassistant: " + "x".repeat(300) + "…\n");
    }

    /** 用户消息保留原文，超过 500 字时截断。 */
    @Test
    void truncatesUserMessageAtFiveHundredChars() {
        String longQuestion = "问".repeat(600);
        ChatMessage user = ChatMessage.user(WORKSPACE_ID, SESSION_ID, longQuestion);

        String formatted = compactor.format(List.of(user));

        assertThat(formatted)
                .isEqualTo("\n## 最近对话\nuser: " + "问".repeat(500) + "…\n");
    }

    /** 无历史且无滚动摘要时返回固定占位。 */
    @Test
    void returnsEmptyHistoryPlaceholder() {
        assertThat(compactor.format(List.of())).isEqualTo("\n## 最近对话\n暂无历史对话。\n");
    }

    /** 滚动摘要段落在最近对话之前注入。 */
    @Test
    void prependsRollingSummaryBeforeRecentTurns() {
        ChatMessage user = ChatMessage.user(WORKSPACE_ID, SESSION_ID, "继续");
        String formatted = compactor.format(List.of(user), "user: 上一轮；assistant: 结论摘要");

        assertThat(formatted)
                .contains("## 更早对话摘要")
                .contains("user: 上一轮；assistant: 结论摘要")
                .contains("## 最近对话")
                .contains("user: 继续");
    }
}
