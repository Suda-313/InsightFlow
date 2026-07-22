package com.insightflow.agent;

import com.insightflow.agent.analyzer.ClassificationAnalyzer;
import com.insightflow.agent.analyzer.RiskAnalyzer;
import com.insightflow.agent.analyzer.SentimentAnalyzer;
import com.insightflow.agent.dto.CellInsight;
import com.insightflow.agent.dto.ClassificationResult;
import com.insightflow.agent.dto.RiskResult;
import com.insightflow.agent.dto.SentimentResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 单元格分析 Agent，并行编排分类、情感和风险三个 Analyzer，
 * 将结果合并为统一的 {@link CellInsight}。
 */
@Component
public class CellAnalysisAgent {

    private final AgentOrchestrator orchestrator;
    private final ClassificationAnalyzer classificationAnalyzer;
    private final SentimentAnalyzer sentimentAnalyzer;
    private final RiskAnalyzer riskAnalyzer;

    public CellAnalysisAgent(AgentOrchestrator orchestrator,
                             ClassificationAnalyzer classificationAnalyzer,
                             SentimentAnalyzer sentimentAnalyzer,
                             RiskAnalyzer riskAnalyzer) {
        this.orchestrator = orchestrator;
        this.classificationAnalyzer = classificationAnalyzer;
        this.sentimentAnalyzer = sentimentAnalyzer;
        this.riskAnalyzer = riskAnalyzer;
    }

    /**
     * 分析单个单元格文本，并行调用三个 Analyzer 并合并结果。
     *
     * @param cellText 单元格文本
     * @return 合并后的单元格洞察
     */
    @SuppressWarnings("unchecked")
    public CellInsight analyze(String cellText) {
        List<InsightAgent<Object>> agents = List.of(
                (InsightAgent<Object>) (InsightAgent<?>) classificationAnalyzer,
                (InsightAgent<Object>) (InsightAgent<?>) sentimentAnalyzer,
                (InsightAgent<Object>) (InsightAgent<?>) riskAnalyzer
        );

        List<Object> results = orchestrator.parallel(agents, cellText);

        ClassificationResult classification = (ClassificationResult) results.get(0);
        SentimentResult sentiment = (SentimentResult) results.get(1);
        RiskResult risk = (RiskResult) results.get(2);

        String summary = buildSummary(classification, cellText);
        List<String> keywords = mergeKeywords(classification, sentiment);

        CellInsight partial = new CellInsight(classification, sentiment, risk, summary, keywords);
        CellInsight empty = new CellInsight(null, null, null, "", List.of());
        return CellInsight.merge(partial, empty);
    }

    private String buildSummary(ClassificationResult classification, String cellText) {
        if (classification != null && classification.reasoning() != null) {
            return classification.reasoning();
        }
        return cellText;
    }

    private List<String> mergeKeywords(ClassificationResult classification, SentimentResult sentiment) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (classification != null && classification.keywords() != null) {
            merged.addAll(classification.keywords());
        }
        if (sentiment != null && sentiment.keywords() != null) {
            merged.addAll(sentiment.keywords());
        }
        return List.copyOf(merged);
    }
}
