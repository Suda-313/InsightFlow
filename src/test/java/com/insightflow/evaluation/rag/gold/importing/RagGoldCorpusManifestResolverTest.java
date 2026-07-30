package com.insightflow.evaluation.rag.gold.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDatasetStatus;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldQuestionType;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** manifest 解析：document_ref + version_no + chunk_no → 公开 UUID。 */
class RagGoldCorpusManifestResolverTest {

    private static final Path MANIFEST = Path.of("evaluation", "rag", "gold", "corpus-manifest.json");

    private RagGoldCorpusManifestResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new RagGoldCorpusManifestResolver(MANIFEST, new ObjectMapper());
    }

    @Test
    void indexesAllPublishedDocuments() {
        assertThat(resolver.documentCount()).isEqualTo(31);
    }

    @Test
    void resolvesKnownChunk() {
        RagGoldSeedFile.EvidenceSeed evidence = new RagGoldSeedFile.EvidenceSeed(
                "CHUNK", "超自然行动组-1.4-版本更新说明", 3, 4);
        RagGoldCorpusManifestResolver.ResolvedEvidence resolved = resolver.resolve("test-case", evidence);
        assertThat(resolved.documentPublicId()).isNotNull();
        assertThat(resolved.versionPublicId()).isNotNull();
        assertThat(resolved.chunkPublicId()).isNotNull();
    }

    @Test
    void failsFastOnMissingDocumentRef() {
        RagGoldSeedFile.EvidenceSeed evidence = new RagGoldSeedFile.EvidenceSeed(
                "CHUNK", "不存在的文档", 1, 1);
        assertThatThrownBy(() -> resolver.resolve("dev-001", evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dev-001")
                .hasMessageContaining("document_ref");
    }

    @Test
    void failsFastOnMissingChunk() {
        RagGoldSeedFile.EvidenceSeed evidence = new RagGoldSeedFile.EvidenceSeed(
                "CHUNK", "超自然行动组-1.4-版本更新说明", 3, 999);
        assertThatThrownBy(() -> resolver.resolve("val-010", evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("val-010")
                .hasMessageContaining("chunk_no");
    }
}
