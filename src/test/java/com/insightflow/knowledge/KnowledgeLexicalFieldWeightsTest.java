package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 加权 trigram 词法 SQL 片段契约：字段与参数名必须与 JdbcKnowledgeVectorStore 一致。 */
class KnowledgeLexicalFieldWeightsTest {

    @Test
    void weightedScoreReferencesAllVisibleFields() {
        String score = KnowledgeLexicalFieldWeights.weightedScoreExpression();

        assertThat(score).contains("visible.title")
                .contains("visible.section_heading")
                .contains("visible.version_no")
                .contains("visible.lexical_text")
                .contains("visible.content")
                .contains(":questionQuery")
                .contains(":expandedQuery");
    }

    @Test
    void matchPredicateUsesSeparateBodyThreshold() {
        String predicate = KnowledgeLexicalFieldWeights.matchPredicate();

        assertThat(predicate).contains(":similarityThreshold")
                .contains(":bodySimilarityThreshold")
                .contains(":expandedQuery")
                .contains("visible.title")
                .contains("visible.section_heading")
                .contains("visible.lexical_text");
    }
}
