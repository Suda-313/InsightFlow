package com.insightflow.evaluation.rag.gold.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagGoldQuestionType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** abstain-50 负样本集与扩展校验规则。 */
class RagGoldAbstainDatasetSeedTest {

    private static final Path MANIFEST = Path.of("evaluation", "rag", "gold", "corpus-manifest.json");
    private static final Path SEED = Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-abstain-50.json");

    private RagGoldSeedValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RagGoldCorpusManifestResolver resolver = new RagGoldCorpusManifestResolver(MANIFEST, mapper);
        validator = new RagGoldSeedValidator(mapper, resolver);
    }

    @Test
    void abstainSeedLoadsWithExpectedDistribution() throws Exception {
        RagGoldSeedFile seed = validator.validateAndParse(SEED);
        assertThat(seed.datasetVersion()).isEqualTo("abstain-50");
        assertThat(seed.cases()).hasSize(50);
        assertThat(seed.cases()).allMatch(RagGoldSeedFile.CaseSeed::shouldRefuse);
        assertThat(RagGoldSeedValidator.countByQuestionType(seed))
                .containsEntry(RagGoldQuestionType.CHITCHAT, 15L)
                .containsEntry(RagGoldQuestionType.NO_ANSWER, 20L)
                .containsEntry(RagGoldQuestionType.REFUSAL, 15L);
    }

    @Test
    void chitchatAndNoAnswerAllowEmptyEvidences() throws Exception {
        RagGoldSeedFile seed = validator.validateAndParse(SEED);
        List<RagGoldSeedFile.CaseSeed> emptyEvidenceCases = seed.cases().stream()
                .filter(c -> c.questionType().equals("CHITCHAT") || c.questionType().equals("NO_ANSWER"))
                .toList();
        assertThat(emptyEvidenceCases).hasSize(35);
        assertThat(emptyEvidenceCases).allMatch(c -> c.evidences().isEmpty());
    }

    @Test
    void refusalCasesRequireEvidences() throws Exception {
        RagGoldSeedFile seed = validator.validateAndParse(SEED);
        assertThat(seed.cases().stream().filter(c -> c.questionType().equals("REFUSAL")))
                .hasSize(15)
                .allMatch(c -> !c.evidences().isEmpty());
    }

    @Test
    void caseKeysUseDevAPrefix() throws Exception {
        RagGoldSeedFile seed = validator.validateAndParse(SEED);
        assertThat(seed.cases()).allMatch(c -> c.caseKey().startsWith("dev-a"));
        assertThat(seed.cases().stream().map(RagGoldSeedFile.CaseSeed::caseKey))
                .doesNotHaveDuplicates();
    }
}
