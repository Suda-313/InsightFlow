package com.insightflow.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * LLM 调用指标工具：记录每次调用的 token 消耗和耗时。
 */
public final class LlmMetrics {

    private static final Logger log = LoggerFactory.getLogger(LlmMetrics.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private LlmMetrics() {}

    /**
     * 从 ChatResponse 中提取 token 用量并记录日志。
     *
     * @param agentName Agent 名称（如 ClassificationAnalyzer）
     * @param startMs   调用开始时间戳
     * @param response  LLM 响应
     */
    public static void log(String agentName, long startMs, ChatResponse response) {
        long elapsed = System.currentTimeMillis() - startMs;
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        if (usage != null) {
            log.info("LLM[{}] 耗时={}ms, promptTokens={}, generationTokens={}, totalTokens={}",
                    agentName, elapsed,
                    usage.getPromptTokens(),
                    usage.getGenerationTokens(),
                    usage.getTotalTokens());
        } else {
            log.info("LLM[{}] 耗时={}ms (token 信息不可用)", agentName, elapsed);
        }
    }

    /**
     * 从 LLM 返回的文本中提取纯 JSON。
     * 处理 markdown 代码块（```json ... ```）和普通文本中嵌入的 JSON。
     */
    public static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String trimmed = raw.trim();
        Matcher blockMatcher = JSON_BLOCK.matcher(trimmed);
        if (blockMatcher.find()) {
            return blockMatcher.group(1).trim();
        }
        Matcher objMatcher = JSON_OBJECT.matcher(trimmed);
        if (objMatcher.find()) {
            return objMatcher.group();
        }
        return trimmed;
    }
}