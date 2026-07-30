package com.insightflow.evaluation.rag.gold.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDatasetStatus;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldQuestionType;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 运营 RAG 金标 400 题 seed 契约：题量、题型/难度分布、manifest 可解析性。
 *
 * <p>ReadService 加载测试见 {@link RagGoldSeedReadServiceContractTest}（需 DB 导入后启用）。</p>
 */
class RagGoldOpsDatasetSeedTest {

    private static final Path MANIFEST = Path.of("evaluation", "rag", "gold", "corpus-manifest.json");

    private static final Map<String, Integer> EXPECTED_COUNTS = Map.of(
            "dev-240", 240,
            "val-80", 80,
            "frozen-80", 80);

    private static final Map<RagGoldQuestionType, Integer> TOTAL_TYPE_QUOTAS = Map.of(
            RagGoldQuestionType.SINGLE_DOCUMENT_FACT, 240,
            RagGoldQuestionType.CROSS_DOCUMENT, 80,
            RagGoldQuestionType.VERSION_CONFLICT, 40,
            RagGoldQuestionType.OPERATION_PROCESS, 24,
            RagGoldQuestionType.WORKSPACE_BOUNDARY, 8,
            RagGoldQuestionType.REFUSAL, 8);

    private static final Map<RagGoldDifficulty, Integer> TOTAL_DIFFICULTY_QUOTAS = Map.of(
            RagGoldDifficulty.EASY, 120,
            RagGoldDifficulty.MEDIUM, 200,
            RagGoldDifficulty.HARD, 80);

    private static RagGoldSeedValidator validator;

    @BeforeAll
    static void loadValidator() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RagGoldCorpusManifestResolver resolver = new RagGoldCorpusManifestResolver(MANIFEST, mapper);
        validator = new RagGoldSeedValidator(mapper, resolver);
    }

    @Test
    void totalCaseCountIs400() throws Exception {
        int total = 0;
        for (String version : EXPECTED_COUNTS.keySet()) {
            RagGoldSeedFile seed = load(version);
            total += seed.cases().size();
        }
        assertThat(total).isEqualTo(400);
    }

    @Test
    void typeDistributionMatchesSpec() throws Exception {
        Map<RagGoldQuestionType, Long> totals = new EnumMap<>(RagGoldQuestionType.class);
        for (RagGoldQuestionType type : RagGoldQuestionType.values()) {
            totals.put(type, 0L);
        }
        for (String version : EXPECTED_COUNTS.keySet()) {
            RagGoldSeedFile seed = load(version);
            RagGoldSeedValidator.countByQuestionType(seed).forEach((type, count) -> totals.merge(type, count, Long::sum));
        }
        TOTAL_TYPE_QUOTAS.forEach((type, expected) ->
                assertThat(totals.get(type)).as("type %s", type).isEqualTo(expected.longValue()));
    }

    @Test
    void difficultyDistributionMatchesSpec() throws Exception {
        Map<RagGoldDifficulty, Long> totals = new EnumMap<>(RagGoldDifficulty.class);
        for (RagGoldDifficulty difficulty : RagGoldDifficulty.values()) {
            totals.put(difficulty, 0L);
        }
        for (String version : EXPECTED_COUNTS.keySet()) {
            RagGoldSeedFile seed = load(version);
            RagGoldSeedValidator.countByDifficulty(seed).forEach((d, count) -> totals.merge(d, count, Long::sum));
        }
        TOTAL_DIFFICULTY_QUOTAS.forEach((d, expected) ->
                assertThat(totals.get(d)).as("difficulty %s", d).isEqualTo(expected.longValue()));
    }

    @Test
    void splitMetadataAndCaseKeyPrefixes() throws Exception {
        assertSplit("dev-240", RagGoldDatasetSplit.DEVELOPMENT, "dev-");
        assertSplit("val-80", RagGoldDatasetSplit.VALIDATION, "val-");
        assertSplit("frozen-80", RagGoldDatasetSplit.FROZEN, "frozen-");
    }

    @Test
    void sourceCorpusVersionMatchesManifest() throws Exception {
        for (String version : EXPECTED_COUNTS.keySet()) {
            RagGoldSeedFile seed = load(version);
            assertThat(seed.sourceCorpusVersion()).isEqualTo("corpus:chaoziran-2026-07-published");
            assertThat(seed.workspacePublicId().toString())
                    .isEqualTo("1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668");
        }
    }

    @Test
    void allEvidencesResolveViaManifest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RagGoldCorpusManifestResolver resolver = new RagGoldCorpusManifestResolver(MANIFEST, mapper);
        for (String version : EXPECTED_COUNTS.keySet()) {
            for (RagGoldSeedFile.CaseSeed goldCase : load(version).cases()) {
                for (RagGoldSeedFile.EvidenceSeed evidence : goldCase.evidences()) {
                    assertThat(resolver.resolve(goldCase.caseKey(), evidence).chunkPublicId())
                            .isNotNull();
                }
            }
        }
    }

    @Test
    void refusalCasesSetShouldRefuse() throws Exception {
        List<RagGoldSeedFile.CaseSeed> all = EXPECTED_COUNTS.keySet().stream()
                .flatMap(v -> {
                    try {
                        return load(v).cases().stream();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
        assertThat(all.stream().filter(c -> c.questionType().equals("REFUSAL")).count()).isEqualTo(8);
        assertThat(all.stream()
                        .filter(c -> c.questionType().equals("REFUSAL"))
                        .filter(RagGoldSeedFile.CaseSeed::shouldRefuse)
                        .count())
                .isEqualTo(8);
    }

    private void assertSplit(String version, RagGoldDatasetSplit split, String prefix) throws Exception {
        RagGoldSeedFile seed = load(version);
        assertThat(seed.split()).isEqualTo(split.name());
        assertThat(seed.cases()).hasSize(EXPECTED_COUNTS.get(version));
        assertThat(seed.cases()).allMatch(c -> c.caseKey().startsWith(prefix));
    }

    private static RagGoldSeedFile load(String version) throws Exception {
        Path path = Path.of("evaluation", "rag", "gold", "seeds", "ops-rag-v1-" + version + ".json");
        return validator.validateAndParse(path);
    }
}
