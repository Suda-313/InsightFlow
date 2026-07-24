package com.insightflow.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 从 classpath 读取并校验首版金标集。
 *
 * <p>评测集随应用版本发布而不是依赖线上数据库，避免线上 CSV 变化导致同一 Prompt 的评测结果不可复现；
 * 真实会话仅能在人工审核后转写为新的脱敏金标样本。</p>
 */
@Component
public class GoldEvaluationDatasetLoader {

    /** 首版金标集资源路径，保持为 main resource 以便未来命令行或管理接口复用。 */
    private static final String RESOURCE_PATH = "evaluation/gold-evaluation-cases.json";

    /** 五类题目各六条，既覆盖用户常见意图，也控制首版人工维护成本。 */
    private static final Map<String, Long> REQUIRED_CATEGORY_COUNTS = Map.of(
            "trend", 6L,
            "alert", 6L,
            "comparison", 6L,
            "refusal", 6L,
            "report", 6L);

    /** Jackson 仅负责反序列化；业务完整性校验必须由本类显式完成。 */
    private final ObjectMapper objectMapper;

    /** 通过构造器注入以便测试使用与生产相同的 JSON 契约。 */
    public GoldEvaluationDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 加载固定金标集；资源缺失或规则不完整属于构建/发布错误，应尽早阻止评测运行。
     */
    public GoldEvaluationDataset load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream input = resource.getInputStream()) {
            GoldEvaluationDataset dataset = objectMapper.readValue(input, GoldEvaluationDataset.class);
            validate(dataset);
            return dataset;
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载金标评测集: " + RESOURCE_PATH, exception);
        }
    }

    /**
     * 校验题量、类别和逐题评分约束，避免无效样本悄然进入后续自动评测结果。
     */
    private void validate(GoldEvaluationDataset dataset) {
        if (dataset == null || !"gold:v1".equals(dataset.version()) || dataset.cases().size() != 30) {
            throw new IllegalStateException("金标评测集版本或题量不符合 gold:v1 契约");
        }
        Map<String, Long> categoryCounts = dataset.cases().stream()
                .collect(Collectors.groupingBy(GoldEvaluationCase::category, Collectors.counting()));
        if (!REQUIRED_CATEGORY_COUNTS.equals(categoryCounts)) {
            throw new IllegalStateException("金标评测集类别覆盖不符合首版契约");
        }
        Set<String> caseIds = dataset.cases().stream().map(GoldEvaluationCase::caseId).collect(Collectors.toSet());
        if (caseIds.size() != dataset.cases().size()) {
            throw new IllegalStateException("金标评测集存在重复 case_id");
        }
        boolean invalidCase = dataset.cases().stream().anyMatch(evaluationCase ->
                isBlank(evaluationCase.fixtureId())
                        || isBlank(evaluationCase.question())
                        || evaluationCase.requiredFacts().isEmpty()
                        || evaluationCase.forbiddenClaims().isEmpty());
        if (invalidCase) {
            throw new IllegalStateException("金标评测集存在缺少评分约束的题目");
        }
    }

    /** 统一处理空白字符串，避免每条规则用例重复书写相同判断。 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
