package com.insightflow.evaluation.rag.gold.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldQuestionType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 校验 seed JSON 的结构、枚举值与业务约束；与 {@code schema.json} 语义一致。
 *
 * <p>不依赖外部 JSON Schema 库，便于在 CI 与导入前做 fail-fast 检查。</p>
 */
public class RagGoldSeedValidator {

    private static final Set<String> SPLITS =
            Set.of("DEVELOPMENT", "VALIDATION", "FROZEN");
    private static final Set<String> QUESTION_TYPES = enumNames(RagGoldQuestionType.class);
    private static final Set<String> DIFFICULTIES = enumNames(RagGoldDifficulty.class);
    private static final Set<String> GRANULARITIES = Set.of("DOCUMENT", "VERSION", "CHUNK");
    private static final Set<String> ASSERTION_TYPES = Set.of("REQUIRED_FACT", "FORBIDDEN_CLAIM");

    private final ObjectMapper objectMapper;
    private final RagGoldCorpusManifestResolver manifestResolver;

    public RagGoldSeedValidator(ObjectMapper objectMapper, RagGoldCorpusManifestResolver manifestResolver) {
        this.objectMapper = objectMapper;
        this.manifestResolver = manifestResolver;
    }

    /** 读取并校验 seed 文件；返回解析后的 DTO。 */
    public RagGoldSeedFile validateAndParse(Path seedPath) throws IOException {
        JsonNode root = objectMapper.readTree(seedPath.toFile());
        validateEnvelope(root, seedPath);
        RagGoldSeedFile seed = objectMapper.treeToValue(root, RagGoldSeedFile.class);
        validateBusinessRules(seed, seedPath);
        return seed;
    }

    /** 统计各题型数量，供测试断言分布。 */
    public static java.util.Map<RagGoldQuestionType, Long> countByQuestionType(RagGoldSeedFile seed) {
        java.util.Map<RagGoldQuestionType, Long> counts = new java.util.EnumMap<>(RagGoldQuestionType.class);
        for (RagGoldQuestionType type : RagGoldQuestionType.values()) {
            counts.put(type, 0L);
        }
        for (RagGoldSeedFile.CaseSeed goldCase : seed.cases()) {
            RagGoldQuestionType type = RagGoldQuestionType.valueOf(goldCase.questionType());
            counts.merge(type, 1L, Long::sum);
        }
        return counts;
    }

    /** 统计各难度数量。 */
    public static java.util.Map<RagGoldDifficulty, Long> countByDifficulty(RagGoldSeedFile seed) {
        java.util.Map<RagGoldDifficulty, Long> counts = new java.util.EnumMap<>(RagGoldDifficulty.class);
        for (RagGoldDifficulty difficulty : RagGoldDifficulty.values()) {
            counts.put(difficulty, 0L);
        }
        for (RagGoldSeedFile.CaseSeed goldCase : seed.cases()) {
            RagGoldDifficulty difficulty = RagGoldDifficulty.valueOf(goldCase.difficulty());
            counts.merge(difficulty, 1L, Long::sum);
        }
        return counts;
    }

    private void validateEnvelope(JsonNode root, Path seedPath) {
        requireText(root, "dataset_key", seedPath);
        requireText(root, "dataset_version", seedPath);
        requireText(root, "split", seedPath);
        requireText(root, "source_corpus_version", seedPath);
        requireText(root, "workspace_public_id", seedPath);
        JsonNode cases = root.get("cases");
        if (cases == null || !cases.isArray() || cases.isEmpty()) {
            throw new IllegalArgumentException(seedPath + ": cases 必须为非空数组");
        }
        String split = root.get("split").asText();
        if (!SPLITS.contains(split)) {
            throw new IllegalArgumentException(seedPath + ": 非法 split " + split);
        }
    }

    private void validateBusinessRules(RagGoldSeedFile seed, Path seedPath) {
        RagGoldDatasetSplit split = RagGoldDatasetSplit.valueOf(seed.split());
        String caseKeyPrefix = expectedCaseKeyPrefix(split);
        Set<String> caseKeys = new HashSet<>();
        for (RagGoldSeedFile.CaseSeed goldCase : seed.cases()) {
            validateCase(goldCase, caseKeyPrefix, seedPath);
            if (!caseKeys.add(goldCase.caseKey())) {
                throw new IllegalArgumentException(
                        seedPath + ": case_key 重复 " + goldCase.caseKey());
            }
        }
    }

    private void validateCase(RagGoldSeedFile.CaseSeed goldCase, String caseKeyPrefix, Path seedPath) {
        if (!goldCase.caseKey().startsWith(caseKeyPrefix)) {
            throw new IllegalArgumentException(
                    seedPath + ": case_key " + goldCase.caseKey() + " 必须以 " + caseKeyPrefix + " 开头");
        }
        if (!QUESTION_TYPES.contains(goldCase.questionType())) {
            throw new IllegalArgumentException(
                    seedPath + ": 非法 question_type " + goldCase.questionType());
        }
        if (!DIFFICULTIES.contains(goldCase.difficulty())) {
            throw new IllegalArgumentException(
                    seedPath + ": 非法 difficulty " + goldCase.difficulty());
        }
        if (!"yangyufei".equals(goldCase.reviewer())) {
            throw new IllegalArgumentException(
                    seedPath + ": reviewer 必须为 yangyufei (case_key=" + goldCase.caseKey() + ")");
        }
        RagGoldQuestionType questionType = RagGoldQuestionType.valueOf(goldCase.questionType());
        if (questionType == RagGoldQuestionType.REFUSAL && !goldCase.shouldRefuse()) {
            throw new IllegalArgumentException(
                    seedPath + ": REFUSAL 题型 must set should_refuse=true (case_key="
                            + goldCase.caseKey()
                            + ")");
        }
        if (questionType != RagGoldQuestionType.REFUSAL && goldCase.shouldRefuse()) {
            throw new IllegalArgumentException(
                    seedPath + ": 非 REFUSAL 题型不应 should_refuse=true (case_key="
                            + goldCase.caseKey()
                            + ")");
        }
        if (goldCase.evidences() == null || goldCase.evidences().isEmpty()) {
            throw new IllegalArgumentException(
                    seedPath + ": evidences 不能为空 (case_key=" + goldCase.caseKey() + ")");
        }
        for (RagGoldSeedFile.EvidenceSeed evidence : goldCase.evidences()) {
            if (!GRANULARITIES.contains(evidence.granularity())) {
                throw new IllegalArgumentException(
                        seedPath + ": 非法 granularity " + evidence.granularity());
            }
            manifestResolver.resolve(goldCase.caseKey(), evidence);
        }
        validateAssertions(goldCase, seedPath);
    }

    private void validateAssertions(RagGoldSeedFile.CaseSeed goldCase, Path seedPath) {
        List<RagGoldSeedFile.AssertionSeed> assertions = goldCase.assertions();
        if (assertions == null || assertions.size() < 2) {
            throw new IllegalArgumentException(
                    seedPath + ": 每题至少 2 条断言 (case_key=" + goldCase.caseKey() + ")");
        }
        boolean hasRequired = false;
        boolean hasForbidden = false;
        for (RagGoldSeedFile.AssertionSeed assertion : assertions) {
            if (!ASSERTION_TYPES.contains(assertion.assertionType())) {
                throw new IllegalArgumentException(
                        seedPath + ": 非法 assertion_type " + assertion.assertionType());
            }
            if (assertion.weight() <= 0) {
                throw new IllegalArgumentException(
                        seedPath + ": assertion weight 必须 > 0 (case_key=" + goldCase.caseKey() + ")");
            }
            if ("REQUIRED_FACT".equals(assertion.assertionType())) {
                hasRequired = true;
            }
            if ("FORBIDDEN_CLAIM".equals(assertion.assertionType())) {
                hasForbidden = true;
            }
        }
        if (!hasRequired || !hasForbidden) {
            throw new IllegalArgumentException(
                    seedPath + ": 每题必须同时含 REQUIRED_FACT 与 FORBIDDEN_CLAIM (case_key="
                            + goldCase.caseKey()
                            + ")");
        }
    }

    private static String expectedCaseKeyPrefix(RagGoldDatasetSplit split) {
        return switch (split) {
            case DEVELOPMENT -> "dev-";
            case VALIDATION -> "val-";
            case FROZEN -> "frozen-";
        };
    }

    private static void requireText(JsonNode root, String field, Path seedPath) {
        JsonNode node = root.get(field);
        if (node == null || node.asText().isBlank()) {
            throw new IllegalArgumentException(seedPath + ": 缺少字段 " + field);
        }
    }

    private static Set<String> enumNames(Class<? extends Enum<?>> enumClass) {
        Set<String> names = new HashSet<>();
        for (Enum<?> constant : enumClass.getEnumConstants()) {
            names.add(constant.name());
        }
        return names;
    }
}
