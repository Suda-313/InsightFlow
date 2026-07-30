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

/** RAG 评测网关须字面量传递含 {@code {版本号}} 的知识片段，不能触发模板解析。 */
class ChatClientRagEvaluationAnswerGatewayTest {

    @Test
    void passesBraceLiteralsToModelWithoutTemplateParsing() {
        LiteralChatModelCaller literalChatModelCaller = mock(LiteralChatModelCaller.class);
        when(literalChatModelCaller.callContent(anyString(), anyString())).thenReturn("ok");
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

        String answer = gateway.answer(
                "「版本档案模板」含 {{placeholder}}，能否当作已发布事实？",
                retrieval);

        assertThat(answer).isEqualTo("ok");
        verify(literalChatModelCaller).callContent(contains("{版本号}"), contains("{{placeholder}}"));
    }
}
