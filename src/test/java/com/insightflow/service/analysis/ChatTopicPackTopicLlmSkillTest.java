package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.prompt.LiteralChatModelCaller;
import com.insightflow.prompt.OperationalPromptCatalog;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** ChatTopicPackTopicLlmSkill：Pack catalog 白名单与 JSON 契约。 */
class ChatTopicPackTopicLlmSkillTest {

    private final LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
    private final TopicPackRegistry registry = topicPackRegistry();

    private static TopicPackRegistry topicPackRegistry() {
        TopicPackRegistry registry = new TopicPackRegistry("game-chaoziran");
        registry.load();
        return registry;
    }

    @Test
    void parsesPackScopedTopicResult() {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn(
                "{\"canonical_key\":\"topic_matchmaking\",\"confidence\":0.88,\"reasoning\":\"匹配慢\"}");
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(chatResponse.getResult()).thenReturn(generation);
        when(chatResponse.getMetadata()).thenReturn(null);
        when(literalChatModelCaller.call(anyString(), anyString())).thenReturn(chatResponse);

        ChatTopicPackTopicLlmSkill skill = new ChatTopicPackTopicLlmSkill(
                literalChatModelCaller, new ObjectMapper(), new OperationalPromptCatalog());
        TopicPackLoader pack = registry.requireByPackId("game-chaoziran");

        Optional<TopicPackTopicLlmSkill.TopicPackTopicLlmResultDto> result =
                skill.classify("匹配等太久了", pack);

        assertThat(result).isPresent();
        assertThat(result.get().canonicalKey()).isEqualTo("topic_matchmaking");
        assertThat(result.get().confidence()).isEqualTo(0.88);
        assertThat(skill.promptVersion()).isEqualTo("pack-topic:v1");
    }

    @Test
    void rejectsOutOfCatalogKey() {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = mock(AssistantMessage.class);
        when(assistantMessage.getText()).thenReturn(
                "{\"canonical_key\":\"topic_unknown\",\"confidence\":0.95,\"reasoning\":\"幻觉键\"}");
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(chatResponse.getResult()).thenReturn(generation);
        when(chatResponse.getMetadata()).thenReturn(null);
        when(literalChatModelCaller.call(anyString(), anyString())).thenReturn(chatResponse);

        ChatTopicPackTopicLlmSkill skill = new ChatTopicPackTopicLlmSkill(
                literalChatModelCaller, new ObjectMapper(), new OperationalPromptCatalog());
        TopicPackLoader pack = registry.requireByPackId("game-chaoziran");

        assertThat(skill.classify("随便说说", pack)).isEmpty();
    }
}

