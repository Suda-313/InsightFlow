package com.insightflow.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ClassificationResult(
    @JsonProperty("canonical_key") String canonicalKey,
    double confidence,
    String reasoning,
    List<String> keywords
) {}