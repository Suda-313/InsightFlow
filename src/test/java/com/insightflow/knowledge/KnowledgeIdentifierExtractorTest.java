package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Phase 2：运营事件编号抽取。 */
class KnowledgeIdentifierExtractorTest {

    @Test
    void extractsHyphenatedAndCompactEventIds() {
        assertThat(KnowledgeIdentifierExtractor.extractEventIds("KI-1301 与 KI1405 是否相同"))
                .containsExactly("KI-1301", "KI-1405");
    }

    @Test
    void containsExactIsCaseInsensitive() {
        assertThat(KnowledgeIdentifierExtractor.containsExact("问题编号 KI-1405 已修复", "ki-1405"))
                .isTrue();
    }
}
