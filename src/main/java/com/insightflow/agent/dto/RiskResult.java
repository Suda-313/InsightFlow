package com.insightflow.agent.dto;

import java.util.List;

public record RiskResult(
    String riskLevel,
    boolean crisisPotential,
    List<String> riskReasons
) {}