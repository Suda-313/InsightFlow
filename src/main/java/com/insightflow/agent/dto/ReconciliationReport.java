package com.insightflow.agent.dto;

import java.util.List;

public record ReconciliationReport(
    boolean ok,
    List<String> mismatches,
    List<Check> checks,
    List<Override> overrides
) {
    public record Check(
        String description,
        boolean passed,
        String detail
    ) {}

    public record Override(
        String field,
        String previousValue,
        String newValue,
        String reason
    ) {}
}