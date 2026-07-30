package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeQueryExpanderTest {

    private final KnowledgeQueryExpander expander = new KnowledgeQueryExpander();

    @Test
    void expandsVersionAndEventTokens() {
        String expanded = expander.expand("KI-1405 在 1.4.1 维护窗口里怎么处理？");

        assertThat(expanded).contains("1.4.1");
        assertThat(expanded).contains("v1.4.1");
        assertThat(expanded).contains("KI-1405");
        assertThat(expanded).contains("KI1405");
        assertThat(expanded).contains("维护");
    }

    @Test
    void keepsOriginalQuestionWhenNoStructuredToken() {
        String question = "普通问题";
        assertThat(expander.expand(question)).isEqualTo(question);
    }
}
