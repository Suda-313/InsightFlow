package com.insightflow.agent.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.InsightAgent;
import com.insightflow.agent.LlmMetrics;
import com.insightflow.agent.dto.RiskResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 游戏运营风险分析助手。
 */
@Component
public class RiskAnalyzer implements InsightAgent<RiskResult> {

    private static final String SYSTEM_PROMPT = """
            你是游戏运营风险分析助手。判断该反馈是否存在公关危机风险。
            - risk_level: none(无), low(低), medium(中), high(高)
            - crisis_potential: 0.0-1.0 危机潜势
            - risk_reasons: 风险原因列表
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public RiskAnalyzer(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public Class<RiskResult> outputSchema() { return RiskResult.class; }

    @Override
    public RiskResult execute(String userInput) {
        long start = System.currentTimeMillis();
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt()).user(userInput).call().chatResponse();
        LlmMetrics.log("Risk", start, response);
        try {
            String content = response.getResult().getOutput().getContent();
            String json = LlmMetrics.extractJson(content);
            return objectMapper.readValue(json, outputSchema());
        } catch (Exception e) { return null; }
    }
}
