package com.insightflow.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.LlmMetrics;
import com.insightflow.agent.dto.TopicPackTopicLlmResult;
import com.insightflow.prompt.LiteralChatModelCaller;
import com.insightflow.prompt.OperationalPromptCatalog;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 基于 ChatClient 的 Pack 级 LLM Topic Skill；仅在选择 Pack catalog 内的 canonical_key。
 *
 * <p>不写入 AgentRun——投影批处理路径与交互式 Agent 分离，避免 1200+ 条导入评论
 * 污染调查 Trace；指标通过 {@link LlmMetrics} 与标注行 topic_llm_* 列观测。</p>
 */
public class ChatTopicPackTopicLlmSkill implements TopicPackTopicLlmSkill {

    private static final Logger log = LoggerFactory.getLogger(ChatTopicPackTopicLlmSkill.class);

    private final LiteralChatModelCaller literalChatModelCaller;
    private final ObjectMapper objectMapper;
    private final OperationalPromptCatalog promptCatalog;

    public ChatTopicPackTopicLlmSkill(
            LiteralChatModelCaller literalChatModelCaller, ObjectMapper objectMapper, OperationalPromptCatalog promptCatalog) {
        this.literalChatModelCaller = literalChatModelCaller;
        this.objectMapper = objectMapper;
        this.promptCatalog = promptCatalog;
    }

    @Override
    public Optional<TopicPackTopicLlmResultDto> classify(String normalizedText, TopicPackLoader pack) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return Optional.empty();
        }
        Set<String> allowedKeys = pack.topics().stream()
                .map(TopicPackTopic::canonicalKey)
                .collect(Collectors.toSet());
        String userPrompt = promptCatalog.renderPackTopicUserPrompt(pack.topics(), normalizedText);
        String version = promptCatalog.packTopic().version();
        long start = System.currentTimeMillis();
        LlmMetrics.logStarted("PackTopic", version, userPrompt);
        ChatResponse response;
        try {
            response = literalChatModelCaller.call(promptCatalog.packTopic().systemPrompt(), userPrompt);
        } catch (RuntimeException exception) {
            LlmMetrics.logFailure("PackTopic", version, start, "model_call");
            log.warn("Pack LLM topic skill model call failed for pack {}: {}", pack.packId(), exception.toString());
            return Optional.empty();
        }
        LlmMetrics.log("PackTopic", version, start, response);
        try {
            String content = response.getResult().getOutput().getText();
            TopicPackTopicLlmResult parsed = objectMapper.readValue(
                    LlmMetrics.extractJson(content), TopicPackTopicLlmResult.class);
            if (parsed.canonicalKey() == null || !allowedKeys.contains(parsed.canonicalKey())) {
                log.warn("Pack LLM returned out-of-catalog key {} for pack {}", parsed.canonicalKey(), pack.packId());
                return Optional.empty();
            }
            return Optional.of(new TopicPackTopicLlmResultDto(parsed.canonicalKey(), parsed.confidence()));
        } catch (Exception exception) {
            LlmMetrics.logFailure("PackTopic", version, start, "response_parse");
            log.warn("Pack LLM topic skill parse failed for pack {}: {}", pack.packId(), exception.toString());
            return Optional.empty();
        }
    }

    @Override
    public String promptVersion() {
        return promptCatalog.packTopic().version();
    }
}
