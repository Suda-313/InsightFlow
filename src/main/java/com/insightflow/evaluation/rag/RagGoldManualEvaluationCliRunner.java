package com.insightflow.evaluation.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.entity.RagEvaluationRun;
import com.insightflow.evaluation.rag.RagGoldManualEvaluationPreviousRunLoader.RagGoldManualEvaluationPreviousRun;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.RagEvaluationRunRepository;
import com.insightflow.service.WorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 命令行入口：由 {@code scripts/run-rag-gold-evaluation.ps1} 触发人工金标 RAG 评测。
 *
 * <p>激活条件：启动参数包含 {@code --rag-gold-eval}。
 * 退出码：0=成功，2=质量回归，3=配置错误，4=部分失败。</p>
 */
@Component
@Profile("!rag-gold-import")
@Conditional(AgentApiKeyPresentCondition.class)
public class RagGoldManualEvaluationCliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RagGoldManualEvaluationCliRunner.class);

    private final RagGoldManualEvaluationRunner evaluationRunner;
    private final RagGoldManualEvaluationRegressionGate regressionGate;
    private final RagGoldManualEvaluationPreviousRunLoader previousRunLoader;
    private final RagEvaluationRunRepository runRepository;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public RagGoldManualEvaluationCliRunner(
            RagGoldManualEvaluationRunner evaluationRunner,
            RagGoldManualEvaluationRegressionGate regressionGate,
            RagGoldManualEvaluationPreviousRunLoader previousRunLoader,
            RagEvaluationRunRepository runRepository,
            WorkspaceService workspaceService,
            ObjectMapper objectMapper) {
        this.evaluationRunner = evaluationRunner;
        this.regressionGate = regressionGate;
        this.previousRunLoader = previousRunLoader;
        this.runRepository = runRepository;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void run(String... args) throws Exception {
        CliArgs cli = CliArgs.parse(args);
        if (!cli.enabled()) {
            return;
        }
        int exitCode;
        try {
            exitCode = execute(cli);
        } catch (IllegalArgumentException exception) {
            log.error("RAG_GOLD_EVAL config_error: {}", exception.getMessage());
            exitCode = 3;
        } catch (Exception exception) {
            log.error("RAG_GOLD_EVAL unexpected_failure", exception);
            exitCode = 3;
        }
        System.exit(exitCode);
    }

    private int execute(CliArgs cli) throws Exception {
        cli.validate();
        UUID workspacePublicId = UUID.fromString(cli.workspacePublicId());
        RagGoldManualEvaluationRunOutcome outcome = executeRun(cli, workspacePublicId);

        RagGoldManualExtendedMetrics extended = outcome.runResult().metrics().extended();
        List<String> regressionViolations = List.of();
        if (cli.baselineRunId() != null && extended != null) {
            RagGoldManualExtendedMetrics baselineMetrics = loadBaselineMetrics(workspacePublicId, cli.baselineRunId());
            regressionViolations = regressionGate.compare(baselineMetrics, extended).violations();
        }

        Map<String, Object> summary = buildSummary(outcome, regressionViolations, cli);
        Path outputDir = Path.of(cli.outputDir());
        Files.createDirectories(outputDir);
        String fileName = "rag-gold-run-" + outcome.runPublicId() + ".json";
        Path outputFile = outputDir.resolve(fileName);
        objectMapper.writeValue(outputFile.toFile(), summary);
        log.info("RAG_GOLD_EVAL summary_written path={}", outputFile.toAbsolutePath());

        if (!regressionViolations.isEmpty()) {
            log.warn("RAG_GOLD_EVAL quality_regression violations={}", regressionViolations);
            return 2;
        }
        if (outcome.hasPartialFailures()) {
            return 4;
        }
        return 0;
    }

    private RagGoldManualEvaluationRunOutcome executeRun(CliArgs cli, UUID workspacePublicId)
            throws Exception {
        if (cli.retryFromRunId() != null) {
            RagGoldManualEvaluationPreviousRun previous = previousRunLoader.loadFromRunId(
                    workspacePublicId, UUID.fromString(cli.retryFromRunId()));
            return evaluationRunner.runRetryFailed(
                    workspacePublicId, cli.datasetKey(), cli.datasetVersion(), previous);
        }
        if (cli.retryFromFile() != null) {
            RagGoldManualEvaluationPreviousRun previous = previousRunLoader.loadFromSummaryFile(
                    Path.of(cli.retryFromFile()));
            return evaluationRunner.runRetryFailed(
                    workspacePublicId, cli.datasetKey(), cli.datasetVersion(), previous);
        }
        if (cli.datasetPublicId() != null) {
            return evaluationRunner.runByPublicId(workspacePublicId, UUID.fromString(cli.datasetPublicId()));
        }
        return evaluationRunner.run(
                workspacePublicId, cli.datasetKey(), cli.datasetVersion(), cli.toRunRequest());
    }

    private RagGoldManualExtendedMetrics loadBaselineMetrics(UUID workspacePublicId, String baselineRunId) throws Exception {
        Workspace workspace = workspaceService.get(workspacePublicId);
        RagEvaluationRun baseline = runRepository
                .findByPublicIdAndWorkspaceId(UUID.fromString(baselineRunId), workspace.getId())
                .orElseThrow(() -> new IllegalArgumentException("基线批次不存在: " + baselineRunId));
        RagEvaluationMetrics metrics = objectMapper.readValue(baseline.getMetricsJson(), RagEvaluationMetrics.class);
        if (metrics.extended() == null) {
            throw new IllegalArgumentException("基线批次缺少扩展指标，无法比较: " + baselineRunId);
        }
        return metrics.extended();
    }

    private Map<String, Object> buildSummary(
            RagGoldManualEvaluationRunOutcome outcome,
            List<String> regressionViolations,
            CliArgs cli) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runPublicId", outcome.runPublicId().toString());
        if (cli.retryFromRunId() != null || cli.retryFromFile() != null) {
            summary.put("retryMode", "failed-only-merge");
            if (cli.retryFromRunId() != null) {
                summary.put("retriedFromRunId", cli.retryFromRunId());
            }
            if (cli.retryFromFile() != null) {
                summary.put("retriedFromFile", cli.retryFromFile());
            }
        }
        summary.put("datasetVersion", outcome.runResult().datasetVersion());
        summary.put("promptVersion", outcome.runResult().promptVersion());
        summary.put("modelName", outcome.runResult().modelName());
        summary.put("retrievalVersion", outcome.runResult().retrievalVersion());
        if (outcome.runResult().metrics().extended() != null) {
            summary.put("evaluationMode", outcome.runResult().metrics().extended().evaluationMode());
        }
        summary.put("metrics", outcome.runResult().metrics());
        summary.put("caseResults", outcome.manualCaseResults());
        summary.put("frozenSplit", outcome.frozenSplit());
        summary.put("partialFailures", outcome.hasPartialFailures());
        summary.put("regressionViolations", regressionViolations);
        summary.put("completedAt", OffsetDateTime.now().toString());
        return summary;
    }

    /** 解析 {@code --rag-gold-eval} 及其子参数。 */
    record CliArgs(
            boolean enabled,
            String workspacePublicId,
            String datasetKey,
            String datasetVersion,
            String datasetPublicId,
            String split,
            String baselineRunId,
            String outputDir,
            String retryFromRunId,
            String retryFromFile,
            String mode,
            String caseKeysFile,
            String embeddingCacheDir,
            String reranker,
            String identifier,
            String subquota,
            String evidenceGate) {

        static CliArgs parse(String... args) {
            boolean enabled = false;
            String workspace = null;
            String datasetKey = null;
            String datasetVersion = null;
            String datasetPublicId = null;
            String split = null;
            String baselineRunId = null;
            String outputDir = "output/rag-gold-runs";
            String retryFromRunId = null;
            String retryFromFile = null;
            String mode = "end-to-end";
            String caseKeysFile = null;
            String embeddingCacheDir = "output/rag-gold-embedding-cache";
            String reranker = "off";
            String identifier = "on";
            String subquota = "on";
            String evidenceGate = "on";
            for (String arg : args) {
                if ("--rag-gold-eval".equals(arg)) {
                    enabled = true;
                    continue;
                }
                if (arg.startsWith("--workspace=")) {
                    workspace = arg.substring("--workspace=".length());
                } else if (arg.startsWith("--dataset-key=")) {
                    datasetKey = arg.substring("--dataset-key=".length());
                } else if (arg.startsWith("--dataset-version=")) {
                    datasetVersion = arg.substring("--dataset-version=".length());
                } else if (arg.startsWith("--dataset-public-id=")) {
                    datasetPublicId = arg.substring("--dataset-public-id=".length());
                } else if (arg.startsWith("--split=")) {
                    split = arg.substring("--split=".length());
                } else if (arg.startsWith("--baseline-run-id=")) {
                    baselineRunId = arg.substring("--baseline-run-id=".length());
                } else if (arg.startsWith("--output-dir=")) {
                    outputDir = arg.substring("--output-dir=".length());
                } else if (arg.startsWith("--retry-from-run=")) {
                    retryFromRunId = arg.substring("--retry-from-run=".length());
                } else if (arg.startsWith("--retry-from-file=")) {
                    retryFromFile = arg.substring("--retry-from-file=".length());
                } else if (arg.startsWith("--mode=")) {
                    mode = arg.substring("--mode=".length());
                } else if (arg.startsWith("--case-keys-file=")) {
                    caseKeysFile = arg.substring("--case-keys-file=".length());
                } else if (arg.startsWith("--embedding-cache-dir=")) {
                    embeddingCacheDir = arg.substring("--embedding-cache-dir=".length());
                } else if (arg.startsWith("--reranker=")) {
                    reranker = arg.substring("--reranker=".length());
                } else if (arg.startsWith("--identifier=")) {
                    identifier = arg.substring("--identifier=".length());
                } else if (arg.startsWith("--subquota=")) {
                    subquota = arg.substring("--subquota=".length());
                } else if (arg.startsWith("--evidence-gate=")) {
                    evidenceGate = arg.substring("--evidence-gate=".length());
                }
            }
            return new CliArgs(
                    enabled,
                    workspace,
                    datasetKey,
                    datasetVersion,
                    datasetPublicId,
                    split,
                    baselineRunId,
                    outputDir,
                    retryFromRunId,
                    retryFromFile,
                    mode,
                    caseKeysFile,
                    embeddingCacheDir,
                    reranker,
                    identifier,
                    subquota,
                    evidenceGate);
        }

        void validate() {
            if (workspacePublicId == null || workspacePublicId.isBlank()) {
                throw new IllegalArgumentException("缺少 --workspace");
            }
            if (retryFromRunId != null && retryFromFile != null) {
                throw new IllegalArgumentException("--retry-from-run 与 --retry-from-file 不能同时使用");
            }
            if ((retryFromRunId != null || retryFromFile != null)
                    && (datasetKey == null || datasetVersion == null)) {
                throw new IllegalArgumentException("重跑失败题需提供 --dataset-key 与 --dataset-version");
            }
            if (datasetPublicId == null && (datasetKey == null || datasetVersion == null)) {
                throw new IllegalArgumentException("必须提供 --dataset-key 与 --dataset-version，或 --dataset-public-id");
            }
            if (!"end-to-end".equals(mode) && !"retrieval-only".equals(mode)) {
                throw new IllegalArgumentException("不支持的 --mode: " + mode);
            }
            if ("retrieval-only".equals(mode) && retryFromRunId != null) {
                throw new IllegalArgumentException("retrieval-only 模式不支持 --retry-from-run");
            }
            if (reranker != null && !reranker.isBlank()
                    && !"on".equalsIgnoreCase(reranker) && !"off".equalsIgnoreCase(reranker)) {
                throw new IllegalArgumentException("不支持的 --reranker: " + reranker + "（仅 on/off）");
            }
            validateOnOffFlag(identifier, "--identifier");
            validateOnOffFlag(subquota, "--subquota");
            validateOnOffFlag(evidenceGate, "--evidence-gate");
        }

        private static void validateOnOffFlag(String value, String flagName) {
            if (value != null && !value.isBlank()
                    && !"on".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("不支持的 " + flagName + ": " + value + "（仅 on/off）");
            }
        }

        boolean rerankerEnabled() {
            return "on".equalsIgnoreCase(reranker);
        }

        boolean identifierSupplementEnabled() {
            return !"off".equalsIgnoreCase(identifier);
        }

        boolean subQueryQuotaEnabled() {
            return !"off".equalsIgnoreCase(subquota);
        }

        boolean evidenceGateEnabled() {
            return !"off".equalsIgnoreCase(evidenceGate);
        }

        RagGoldEvaluationRunRequest toRunRequest() throws java.io.IOException {
            java.util.Set<String> caseKeys = caseKeysFile == null
                    ? java.util.Set.of()
                    : RagGoldCaseKeyFileLoader.load(java.nio.file.Path.of(caseKeysFile));
            if ("retrieval-only".equals(mode)) {
                return RagGoldEvaluationRunRequest.retrievalOnly(
                        caseKeys,
                        java.nio.file.Path.of(embeddingCacheDir),
                        rerankerEnabled(),
                        identifierSupplementEnabled(),
                        subQueryQuotaEnabled(),
                        evidenceGateEnabled());
            }
            if (!caseKeys.isEmpty()) {
                return new RagGoldEvaluationRunRequest(
                        RagGoldEvaluationRunMode.END_TO_END,
                        caseKeys,
                        null,
                        false,
                        rerankerEnabled(),
                        identifierSupplementEnabled(),
                        subQueryQuotaEnabled(),
                        evidenceGateEnabled());
            }
            return new RagGoldEvaluationRunRequest(
                    RagGoldEvaluationRunMode.END_TO_END,
                    Set.of(),
                    null,
                    false,
                    rerankerEnabled(),
                    identifierSupplementEnabled(),
                    subQueryQuotaEnabled(),
                    evidenceGateEnabled());
        }
    }
}
