package com.insightflow.evaluation.rag.gold.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldQuestionType;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** seed 文件结构校验与 manifest 证据解析。 */
class RagGoldSeedValidatorTest {

    private static final Path MANIFEST = Path.of("evaluation", "rag", "gold", "corpus-manifest.json");

    private RagGoldSeedValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RagGoldCorpusManifestResolver resolver = new RagGoldCorpusManifestResolver(MANIFEST, mapper);
        validator = new RagGoldSeedValidator(mapper, resolver);
    }

    @Test
    void validatesDevSeedFile() throws Exception {
        RagGoldSeedFile seed = validator.validateAndParse(seedPath("dev-240"));
        assertThat(seed.cases()).hasSize(240);
        assertThat(seed.split()).isEqualTo("DEVELOPMENT");
        assertThat(seed.datasetKey()).isEqualTo("ops-rag-v1");
    }

    @Test
    void validatesValAndFrozenSeedFiles() throws Exception {
        assertThat(validator.validateAndParse(seedPath("val-80")).cases()).hasSize(80);
        assertThat(validator.validateAndParse(seedPath("frozen-80")).cases()).hasSize(80);
    }

    @Test
    void allSeedCaseKeysAreUniqueAcross400() throws Exception {
        var keys = java.util.stream.Stream.of("dev-240", "val-80", "frozen-80")
                .flatMap(s -> {
                    try {
                        return validator.validateAndParse(seedPath(s)).cases().stream()
                                .map(RagGoldSeedFile.CaseSeed::caseKey);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
        assertThat(keys).hasSize(400).doesNotHaveDuplicates();
    }

    private static Path seedPath(String version) {
        return Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-" + version + ".json");
    }
}
