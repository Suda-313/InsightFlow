package com.insightflow.agent.dto;

import java.util.List;

public record ReportDraft(
    String executiveSummary,
    List<String> highlights,
    List<String> recommendations,
    List<RiskAlert> riskAlerts
) {
    public record RiskAlert(
        String level,
        String description,
        String affectedArea,
        String issue,
        int mentions
    ) {}
}