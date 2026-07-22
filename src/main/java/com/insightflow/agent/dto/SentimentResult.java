package com.insightflow.agent.dto;

import java.util.List;

public record SentimentResult(
    String sentiment,
    String urgency,
    List<String> keywords
) {}