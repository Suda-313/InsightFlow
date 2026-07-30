package com.insightflow.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Pack 级 LLM Topic Skill 的 JSON 输出契约；canonical_key 须来自当前 Pack catalog。
 */
public record TopicPackTopicLlmResult(
        @JsonProperty("canonical_key") String canonicalKey,
        double confidence,
        String reasoning) {
}
