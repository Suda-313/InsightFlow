package com.insightflow.agent.report;

import com.insightflow.agent.dto.ReconciliationReport;
import com.insightflow.agent.dto.ReportDraft;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 报告生成 Agent：LLM 生成草稿 -> 纯代码对账 -> LLM 修正 -> 再次对账。
 */
@Component
public class ReportAgent {

    private static final String GENERATE_PROMPT = """
            你是游戏客服数据分析助手。根据以下聚合数据生成一份运营周报草稿：
            - 实际总工单数：%d
            - 主题分布：%s

            请返回 JSON 格式：
            {
              "executiveSummary": "本周共 %d 条工单，主要问题为...",
              "highlights": ["要点1", "要点2"],
              "recommendations": ["建议1", "建议2"],
              "riskAlerts": [
                {"level": "high", "description": "...", "affectedArea": "...", "issue": "issueKey", "mentions": 0}
              ]
            }
            """;

    private static final String REVISE_PROMPT = """
            请根据对账报告修正以下报告草稿：
            %s

            对账报告：
            %s

            请返回修正后的 JSON 格式报告，确保数字与对账报告一致。
            """;

    private final ChatClient chatClient;
    private final ReconciliationEngine reconciliationEngine;
    private final ReportTools reportTools;

    public ReportAgent(ChatClient chatClient,
                       ReconciliationEngine reconciliationEngine,
                       ReportTools reportTools) {
        this.chatClient = chatClient;
        this.reconciliationEngine = reconciliationEngine;
        this.reportTools = reportTools;
    }

    public ReportResult generate(MergedData mergedData) {
        ReportDraft draft = generateDraft(mergedData);
        ReconciliationReport reconciliation = reconciliationEngine.reconcile(
                draft, mergedData.actualTicketCount(), mergedData.issueMentions());

        if (reconciliation.ok()) {
            return new ReportResult(draft, reconciliation);
        }

        ReportDraft revised = reviseDraft(draft, reconciliation);
        ReconciliationReport finalReconciliation = reconciliationEngine.reconcile(
                revised, mergedData.actualTicketCount(), mergedData.issueMentions());
        return new ReportResult(revised, finalReconciliation);
    }

    private ReportDraft generateDraft(MergedData mergedData) {
        String userPrompt = String.format(GENERATE_PROMPT,
                mergedData.actualTicketCount(),
                mergedData.issueMentions().toString(),
                mergedData.actualTicketCount());

        return chatClient.prompt()
                .system("你是游戏客服数据分析助手，请生成结构化的运营周报。")
                .user(userPrompt)
                .call()
                .entity(ReportDraft.class);
    }

    private ReportDraft reviseDraft(ReportDraft draft, ReconciliationReport reconciliation) {
        String userPrompt = String.format(REVISE_PROMPT,
                draft.toString(),
                reconciliation.toString());

        return chatClient.prompt()
                .system("你是游戏客服数据分析助手，请根据对账报告修正报告草稿中的数字。")
                .user(userPrompt)
                .call()
                .entity(ReportDraft.class);
    }
}
