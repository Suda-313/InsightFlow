package com.insightflow.evaluation.rag.gold.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** multiturn-40 多轮指代评测集契约。 */
class RagGoldMultiTurnDatasetSeedTest {

    private static final Path MANIFEST = Path.of("evaluation", "rag", "gold", "corpus-manifest.json");
    private static final Path SEED = Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-multiturn-40.json");
    private static final Path DEV240 = Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-dev-240.json");
    private static final Path SOURCE_KEYS =
            Path.of("evaluation", "rag", "gold", "multiturn-source-keys.txt");
    private static final Pattern DERIVED_FROM = Pattern.compile("^multiturn-derived-from:dev-\\d{3}$");

    private RagGoldSeedValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RagGoldCorpusManifestResolver resolver = new RagGoldCorpusManifestResolver(MANIFEST, mapper);
        validator = new RagGoldSeedValidator(mapper, resolver);
    }

    @Test
    void multiturnSeedLoadsWithFortyCases() throws Exception {
        RagGoldSeedFile seed = validator.validateAndParse(SEED);
        assertThat(seed.datasetVersion()).isEqualTo("multiturn-40");
        assertThat(seed.cases()).hasSize(40);
    }

    @Test
    void eachCaseHasTwoContextTurns() throws Exception {
        RagGoldSeedFile seed = validator.validateAndParse(SEED);
        assertThat(seed.cases()).allMatch(c -> c.contextTurns() != null && c.contextTurns().size() == 2);
        assertThat(seed.cases()).allMatch(c -> "user".equals(c.contextTurns().get(0).role()));
        assertThat(seed.cases()).allMatch(c -> "assistant".equals(c.contextTurns().get(1).role()));
    }

    @Test
    void annotationBasisReferencesDev240Source() throws Exception {
        RagGoldSeedFile seed = validator.validateAndParse(SEED);
        RagGoldSeedFile dev240 = validator.validateAndParse(DEV240);
        List<String> devKeys = dev240.cases().stream().map(RagGoldSeedFile.CaseSeed::caseKey).toList();

        assertThat(seed.cases()).allMatch(c -> DERIVED_FROM.matcher(c.annotationBasis()).matches());
        for (RagGoldSeedFile.CaseSeed goldCase : seed.cases()) {
            String sourceKey = goldCase.annotationBasis().substring("multiturn-derived-from:".length());
            assertThat(devKeys).contains(sourceKey);
        }
    }

    @Test
    void sourceKeysFileMatchesDerivedCases() throws Exception {
        RagGoldSeedFile seed = validator.validateAndParse(SEED);
        List<String> keysFromFile = Files.readAllLines(SOURCE_KEYS).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        assertThat(keysFromFile).hasSize(40);
        List<String> keysFromSeed = seed.cases().stream()
                .map(c -> c.annotationBasis().substring("multiturn-derived-from:".length()))
                .toList();
        assertThat(keysFromFile).containsExactlyElementsOf(keysFromSeed);
    }
}
