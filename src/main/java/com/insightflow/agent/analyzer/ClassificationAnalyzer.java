package com.insightflow.agent.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.InsightAgent;
import com.insightflow.agent.LlmMetrics;
import com.insightflow.agent.dto.ClassificationResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 游戏客服工单分类助手。
 */
@Component
public class ClassificationAnalyzer implements InsightAgent<ClassificationResult> {

    private static final String SYSTEM_PROMPT = """
            你是游戏客服工单分类助手。根据工单文本，判断它属于哪个问题类别。
            - 只能从已知类别中选择：login_failure(登录失败), payment_recharge(充值异常),
              item_loss(道具丢失), account_recovery(账号找回), bug_gameplay(玩法bug),
              bug_network(网络问题), violation_report(违规举报), suggestion(建议反馈)
            - 如果确实不属于任何类别，返回 canonical_key="unclassified"
            - confidence 表示你的确信度（0.0-1.0）
            - reasoning 用一句话解释分类理由
            - keywords 提取3-5个关键词
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public ClassificationAnalyzer(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String systemPrompt() { return SYSTEM_PROMPT; }

    @Override
    public Class<ClassificationResult> outputSchema() { return ClassificationResult.class; }

    @Override
    public ClassificationResult execute(String userInput) {
        long start = System.currentTimeMillis();
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt())
                .user(userInput)
                .call()
                .chatResponse();
        LlmMetrics.log("Classification", start, response);
        try {
            String content = response.getResult().getOutput().getContent();
            String json = LlmMetrics.extractJson(content);
            return objectMapper.readValue(json, outputSchema());
        } catch (Exception e) {
            return null;
        }
    }
}
