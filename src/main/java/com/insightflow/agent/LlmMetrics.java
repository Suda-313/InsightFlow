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
     * 在调用模型前记录可观测边界；只保留输入长度，禁止将脱敏反馈正文写入应用日志。
     */
    public static void logStarted(String agentName, String input) {
        log.info("LLM[{}] status=started, input_chars={}", agentName, input == null ? 0 : input.length());
    }

    /** 记录带 Prompt 版本的调用开始事件，使同一 Agent 的提示词迭代可在日志中直接区分。 */
    public static void logStarted(String agentName, String promptVersion, String input) {
        log.info("LLM[{}] status=started, prompt_version={}, input_chars={}",
                agentName, promptVersion, input == null ? 0 : input.length());
    }

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
            log.info("LLM[{}] status=succeeded, latency_ms={}, prompt_tokens={}, completion_tokens={}, total_tokens={}",
                    agentName, elapsed,
                    usage.getPromptTokens(),
                    usage.getGenerationTokens(),
                    usage.getTotalTokens());
        } else {
            log.info("LLM[{}] status=succeeded, latency_ms={}, token 信息不可用", agentName, elapsed);
        }
    }

    /**
     * 模型请求或结构化结果解析失败时只记录阶段和耗时，异常文本可能包含输入或模型输出，不能直接透传。
     */
    /** 成功日志同时记录 Prompt 版本与用量，缺失 Usage 时仍不伪造 Token 成本。 */
    public static void log(String agentName, String promptVersion, long startMs, ChatResponse response) {
        long elapsed = System.currentTimeMillis() - startMs;
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        if (usage != null) {
            log.info("LLM[{}] status=succeeded, prompt_version={}, latency_ms={}, prompt_tokens={}, completion_tokens={}, total_tokens={}",
                    agentName, promptVersion, elapsed,
                    usage.getPromptTokens(), usage.getGenerationTokens(), usage.getTotalTokens());
        } else {
            log.info("LLM[{}] status=succeeded, prompt_version={}, latency_ms={}, token 信息不可用",
                    agentName, promptVersion, elapsed);
        }
    }

    public static void logFailure(String agentName, long startMs, String stage) {
        log.warn("LLM[{}] status=failed, stage={}, latency_ms={}",
                agentName, stage, System.currentTimeMillis() - startMs);
    }

    /** 失败事件也保留版本维度，避免无法判断故障是否只发生在某次 Prompt 迭代。 */
    public static void logFailure(String agentName, String promptVersion, long startMs, String stage) {
        log.warn("LLM[{}] status=failed, prompt_version={}, stage={}, latency_ms={}",
                agentName, promptVersion, stage, System.currentTimeMillis() - startMs);
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
