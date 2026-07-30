package com.insightflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeRerankDocumentTextTest {

    @Test
    void includesTitleTypeVersionSectionAndContent() {
        UUID docId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeVectorStore.SearchCandidate candidate = new KnowledgeVectorStore.SearchCandidate(
                docId,
                versionId,
                2,
                chunkId,
                "登录异常说明",
                "玩家无法进入大厅",
                0.5d,
                "KNOWN_ISSUE",
                "故障现象",
                "2024-01-01..2024-12-31");

        String text = KnowledgeRerankDocumentText.forCandidate(candidate);

        assertThat(text).contains("title: 登录异常说明");
        assertThat(text).contains("type: KNOWN_ISSUE");
        assertThat(text).contains("version: v2");
        assertThat(text).contains("section: 故障现象");
        assertThat(text).contains("content: 玩家无法进入大厅");
        assertThat(text).contains("effective: 2024-01-01..2024-12-31");
    }
}
