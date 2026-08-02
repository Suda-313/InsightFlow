package com.insightflow.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RiskResult(
    @JsonProperty("risk_level") String riskLevel,
    @JsonProperty("crisis_potential") double crisisPotential,
    @JsonProperty("risk_reasons") List<String> riskReasons
) {}