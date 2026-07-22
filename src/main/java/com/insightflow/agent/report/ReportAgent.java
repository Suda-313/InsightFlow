package com.insightflow.agent.report;

import com.insightflow.agent.LlmMetrics;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import com.insightflow.agent.report.MergedData;

/**
 * 报告生成 Agent：LLM 生成运营周报文字叙述。
 */
@Component
public class ReportAgent {

    private final ChatClient chatClient;

    public ReportAgent(ChatClient chatClient,
                       ReconciliationEngine reconciliationEngine,
                       ReportTools reportTools) {
        this.chatClient = chatClient;
    }

    /**
     * 生成报告，返回 LLM 生成的文字叙述。
     */
    public String generate(MergedData mergedData) {
        String userPrompt = "你是游戏客服数据分析助手。根据以下聚合数据生成一份运营周报：\n"
                + "- 实际总工单数：" + mergedData.actualTicketCount() + "\n"
                + "- 主题分布：" + mergedData.issueMentions().toString() + "\n\n"
                + "请生成一份包含执行摘要、要点、建议和风险提示的报告。";

        long start = System.currentTimeMillis();
        ChatResponse response = chatClient.prompt()
                .system("你是游戏客服数据分析助手，请生成运营周报。")
                .user(u -> u.text(userPrompt))
                .call()
                .chatResponse();
        LlmMetrics.log("Report", start, response);
        return response.getResult().getOutput().getContent();
    }
}
