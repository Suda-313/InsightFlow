package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 证据粒度与公开 UUID 组合校验。 */
class RagGoldCaseEvidenceTest {

    private static final UUID DOC = UUID.randomUUID();
    private static final UUID VER = UUID.randomUUID();
    private static final UUID CHUNK = UUID.randomUUID();

    @Test
    void documentGranularityAllowsDocumentOnly() {
        RagGoldCaseEvidence.create(7L, 11L, RagGoldEvidenceGranularity.DOCUMENT, DOC, null, null, 0);
    }

    @Test
    void versionGranularityRequiresVersionPublicId() {
        assertThatThrownBy(() -> RagGoldCaseEvidence.create(
                        7L, 11L, RagGoldEvidenceGranularity.VERSION, DOC, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version_public_id");
    }

    @Test
    void chunkGranularityRequiresVersionAndChunkPublicIds() {
        assertThatThrownBy(() -> RagGoldCaseEvidence.create(
                        7L, 11L, RagGoldEvidenceGranularity.CHUNK, DOC, VER, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk_public_id");
    }

    @Test
    void chunkGranularityAcceptsFullPublicIdChain() {
        RagGoldCaseEvidence.create(7L, 11L, RagGoldEvidenceGranularity.CHUNK, DOC, VER, CHUNK, 0);
    }
}
