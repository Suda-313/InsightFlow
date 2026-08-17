package com.insightflow.prompt;

import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 不经 {@link org.springframework.ai.chat.client.ChatClient} 模板引擎，直接把 system/user 文本发给模型。
 *
 * <p>ChatClient 会把 {@code {…}} / {@code {{…}}} 当作 StringTemplate 变量解析；知识库模板文档、
 * 用户反馈与金标题干常含 {@code {版本号}}、{@code {{version}}} 等字面量，解析失败会在毫秒级
 * 抛出 {@code invalid character} 并误判为 generation_failed。本类用 {@link ChatModel#call(Prompt)}
 * 逐字传递消息，避免该问题。</p>
 */
public class LiteralChatModelCaller {

    private final ChatModel chatModel;

    public LiteralChatModelCaller(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 同步调用聊天模型并返回完整响应（含 Usage 等元数据）。
     *
     * @param systemText 系统护栏与受控证据，可含任意字面量大括号
     * @param userText 用户问题或结构化输入
     */
    public ChatResponse call(String systemText, String userText) {
        return chatModel.call(new Prompt(List.of(
                new SystemMessage(systemText == null ? "" : systemText),
                new UserMessage(userText == null ? "" : userText))));
    }

    /** 只取助手正文；空响应归一化为空字符串，与 ChatClient {@code .content()} 行为一致。 */
    public String callContent(String systemText, String userText) {
        ChatResponse response = call(systemText, userText);
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String content = response.getResult().getOutput().getText();
        return content == null ? "" : content;
    }
}
