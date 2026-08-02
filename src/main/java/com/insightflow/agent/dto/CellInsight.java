package com.insightflow.agent.dto;

import java.util.ArrayList;
import java.util.List;

public record CellInsight(
    ClassificationResult classification,
    SentimentResult sentiment,
    RiskResult risk,
    String summary,
    List<String> keywords
) {
    public static CellInsight merge(CellInsight a, CellInsight b) {
        var mergedKeywords = new ArrayList<>(a.keywords());
        for (var kw : b.keywords()) {
            if (!mergedKeywords.contains(kw)) {
                mergedKeywords.add(kw);
            }
        }
        return new CellInsight(
            a.classification(),
            a.sentiment(),
            a.risk(),
            a.summary() + "\n---\n" + b.summary(),
            List.copyOf(mergedKeywords)
        );
    }
}