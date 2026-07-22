package com.insightflow.agent.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.InsightAgent;
import com.insightflow.agent.LlmMetrics;
import com.insightflow.agent.dto.SentimentResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 游戏客服情感分析助手。
 */
@Component
public class SentimentAnalyzer implements InsightAgent<SentimentResult> {

    private static final String SYSTEM_PROMPT = """
            你是游戏客服情感分析助手。判断玩家情绪和紧急程度。
            - sentiment: positive(满意), neutral(中性), negative(不满), angry(愤怒)
            - urgency: low(低), medium(中), high(高), critical(紧急)
            - keywords: 提取情感关键词
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SentimentAnalyzer(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public Class<SentimentResult> outputSchema() { return SentimentResult.class; }

    @Override
    public SentimentResult execute(String userInput) {
        long start = System.currentTimeMillis();
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt()).user(userInput).call().chatResponse();
        LlmMetrics.log("Sentiment", start, response);
        try {
            String content = response.getResult().getOutput().getContent();
            String json = LlmMetrics.extractJson(content);
            return objectMapper.readValue(json, outputSchema());
        } catch (Exception e) { return null; }
    }
}
