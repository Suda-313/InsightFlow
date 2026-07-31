package com.insightflow.evaluation.rag.gold.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldQuestionType;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void chitchatAllowsEmptyEvidences() throws Exception {
        RagGoldSeedFile.CaseSeed chitchat = new RagGoldSeedFile.CaseSeed(
                "dev-a99",
                "你好",
                "CHITCHAT",
                "EASY",
                true,
                "测试",
                "yangyufei",
                0,
                List.of(),
                List.of(
                        new RagGoldSeedFile.AssertionSeed("REQUIRED_FACT", "问候", 1.0),
                        new RagGoldSeedFile.AssertionSeed("FORBIDDEN_CLAIM", "编造", 1.0)));
        assertThatCode(() -> validateSyntheticCase(chitchat)).doesNotThrowAnyException();
    }

    @Test
    void refusalEmptyEvidencesRejected() {
        RagGoldSeedFile.CaseSeed refusal = new RagGoldSeedFile.CaseSeed(
                "dev-a98",
                "查询玩家隐私",
                "REFUSAL",
                "HARD",
                true,
                "测试",
                "yangyufei",
                0,
                List.of(),
                List.of(
                        new RagGoldSeedFile.AssertionSeed("REQUIRED_FACT", "拒答", 1.0),
                        new RagGoldSeedFile.AssertionSeed("FORBIDDEN_CLAIM", "编造", 1.0)));
        assertThatThrownBy(() -> validateSyntheticCase(refusal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidences 不能为空");
    }

    @Test
    void nonAbstainTypeCannotSetShouldRefuse() {
        RagGoldSeedFile.CaseSeed invalid = new RagGoldSeedFile.CaseSeed(
                "dev-001",
                "1.4 上线窗口？",
                "SINGLE_DOCUMENT_FACT",
                "EASY",
                true,
                "测试",
                "yangyufei",
                0,
                List.of(new RagGoldSeedFile.EvidenceSeed("CHUNK", "超自然行动组-1.4-版本更新说明", 5, 1)),
                List.of(
                        new RagGoldSeedFile.AssertionSeed("REQUIRED_FACT", "事实", 1.0),
                        new RagGoldSeedFile.AssertionSeed("FORBIDDEN_CLAIM", "编造", 1.0)));
        assertThatThrownBy(() -> validateSyntheticCase(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("should_refuse");
    }

    @Test
    void contextTurnsRejectInvalidRole() throws Exception {
        RagGoldSeedFile seed = loadFirstDevCaseWithContextTurns(List.of(
                new RagGoldSeedFile.ContextTurn("system", "非法角色")));
        assertThatThrownBy(() -> validator.validateAndParse(writeTempSeed(seed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context_turns.role");
    }

    private void validateSyntheticCase(RagGoldSeedFile.CaseSeed goldCase) throws Exception {
        RagGoldSeedFile seed = new RagGoldSeedFile(
                "ops-rag-v1",
                "synthetic",
                "DEVELOPMENT",
                "corpus:chaoziran-2026-07-published",
                java.util.UUID.fromString("1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668"),
                List.of(goldCase));
        Path temp = java.nio.file.Files.createTempFile("rag-gold-seed-", ".json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), seed);
        validator.validateAndParse(temp);
    }

    private RagGoldSeedFile loadFirstDevCaseWithContextTurns(List<RagGoldSeedFile.ContextTurn> turns)
            throws Exception {
        RagGoldSeedFile dev = validator.validateAndParse(seedPath("dev-240"));
        RagGoldSeedFile.CaseSeed base = dev.cases().get(0);
        RagGoldSeedFile.CaseSeed withTurns = new RagGoldSeedFile.CaseSeed(
                base.caseKey(),
                base.questionText(),
                base.questionType(),
                base.difficulty(),
                base.shouldRefuse(),
                base.annotationBasis(),
                base.reviewer(),
                base.sortOrder(),
                base.evidences(),
                base.assertions(),
                turns);
        return new RagGoldSeedFile(
                dev.datasetKey(),
                "synthetic",
                dev.split(),
                dev.sourceCorpusVersion(),
                dev.workspacePublicId(),
                List.of(withTurns));
    }

    private Path writeTempSeed(RagGoldSeedFile seed) throws Exception {
        Path temp = java.nio.file.Files.createTempFile("rag-gold-seed-", ".json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), seed);
        return temp;
    }

    private static Path seedPath(String version) {
        return Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-" + version + ".json");
    }
}
