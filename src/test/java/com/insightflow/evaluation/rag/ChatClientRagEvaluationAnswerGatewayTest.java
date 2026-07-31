package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.knowledge.KnowledgeEvidence;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.prompt.ChatPromptTemplate;
import com.insightflow.prompt.LiteralChatModelCaller;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

/** RAG 评测网关须字面量传递含 {@code {版本号}} 的知识片段，不能触发模板解析。 */
class ChatClientRagEvaluationAnswerGatewayTest {

    @Test
    void passesBraceLiteralsToModelWithoutTemplateParsing() {
        LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
        ChatResponse response = mock(ChatResponse.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        Generation generation = mock(Generation.class);
        AssistantMessage assistant = mock(AssistantMessage.class);
        Usage usage = mock(Usage.class);
        when(literalChatModelCaller.call(anyString(), anyString())).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistant);
        when(assistant.getContent()).thenReturn("ok");
        when(response.getMetadata().getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(100L);
        when(usage.getGenerationTokens()).thenReturn(20L);
        when(usage.getTotalTokens()).thenReturn(120L);

        ChatClientRagEvaluationAnswerGateway gateway = new ChatClientRagEvaluationAnswerGateway(
                literalChatModelCaller, new ChatPromptTemplate());
        KnowledgeRetrievalResult retrieval = new KnowledgeRetrievalResult(
                1,
                List.of(new KnowledgeEvidence(
                        "knowledge:doc:v1:chunk1",
                        "版本档案模板",
                        1,
                        "超自然行动组-{版本号}-版本档案.md",
                        "/src")));

        RagEvaluationGenerationResult result = gateway.answer(
                "「版本档案模板」含 {{placeholder}}，能否当作已发布事实？",
                retrieval);

        assertThat(result.answer()).isEqualTo("ok");
        assertThat(result.promptTokens()).isEqualTo(100L);
        assertThat(result.completionTokens()).isEqualTo(20L);
        assertThat(result.totalTokens()).isEqualTo(120L);
        verify(literalChatModelCaller).call(contains("{版本号}"), contains("{{placeholder}}"));
    }
}
