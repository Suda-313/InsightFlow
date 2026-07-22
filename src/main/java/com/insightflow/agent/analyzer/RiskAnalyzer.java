package com.insightflow.agent.analyzer;

import com.insightflow.agent.InsightAgent;
import com.insightflow.agent.dto.RiskResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 游戏运营风险分析助手。
 */
@Component
public class RiskAnalyzer implements InsightAgent<RiskResult> {

    private static final String SYSTEM_PROMPT = """
            你是游戏运营风险分析助手。判断该反馈是否存在公关危机风险。
            - riskLevel: none(无), low(低), medium(中), high(高)
            - crisisPotential: true 表示存在危机潜势，false 表示不存在
            - riskReasons: 风险原因列表
            """;

    private final ChatClient chatClient;

    public RiskAnalyzer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    public Class<RiskResult> outputSchema() {
        return RiskResult.class;
    }

    @Override
    public RiskResult execute(String userInput) {
        return chatClient.prompt()
                .system(systemPrompt())
                .user(userInput)
                .call()
                .entity(outputSchema());
    }
}
