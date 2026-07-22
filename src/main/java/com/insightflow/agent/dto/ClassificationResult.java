package com.insightflow.agent.dto;

import java.util.List;

public record ClassificationResult(
    String canonicalKey,
    double confidence,
    String reasoning,
    List<String> keywords
) {}