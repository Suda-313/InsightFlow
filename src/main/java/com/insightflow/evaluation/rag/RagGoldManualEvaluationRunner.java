package com.insightflow.evaluation.rag;

import com.insightflow.agent.investigation.ContextTurn;
import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.evaluation.rag.RagGoldManualEvaluationPreviousRunLoader.RagGoldManualEvaluationPreviousRun;
import com.insightflow.evaluation.rag.gold.RagGoldCaseSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldDatasetSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldDatasetReadService;
import com.insightflow.knowledge.KnowledgeRetrievalDiagnostics;
import com.insightflow.knowledge.KnowledgeRetrievalOptions;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeSearchTool;
import com.insightflow.prompt.ChatPromptTemplate;
import com.insightflow.service.RagEvaluationHistoryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

/**
 * 人工金标 RAG 评测 Runner：加载已发布/冻结数据集快照，逐题执行受控检索与生成并评分。
 *
 * <p>单题失败不中断批次；FROZEN split 的逐题日志与持久化结果严格脱敏。
 * 生产质量门禁应使用本 Runner，而非 {@link RagLiveEvaluationRunner}。</p>
 */
@Service
@Conditional(AgentApiKeyPresentCondition.class)
public class RagGoldManualEvaluationRunner {

    private static final Pattern KNOWLEDGE_CITATION = Pattern.compile("\\[证据:\\s*(knowledge:[^\\]\\s]+)\\]");

    /** 逐题日志只含 case_key、阶段与耗时，不输出问题、回答或 chunk 正文。 */
    private static final Logger log = LoggerFactory.getLogger(RagGoldManualEvaluationRunner.class);

    private final RagGoldDatasetReadService datasetReadService;
    private final RagEvaluationCaseExecutor caseExecutor;
    private final RagGoldRetrievalCaseExecutor retrievalCaseExecutor;
    private final RagGoldEvidenceMatcher evidenceMatcher;
    private final RagGoldManualEvaluationScorer manualScorer;
    private final RagEvaluationHistoryService historyService;
    private final RagGoldManualEvaluationPreviousRunLoader previousRunLoader;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final ChatPromptTemplate promptTemplate = new ChatPromptTemplate();

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String configuredModelName = "unknown";

    @Value("${insightflow.agent.fallback-chat-model:}")
    private String fallbackChatModel = "";

    @Value("${insightflow.knowledge.embedding-model:text-embedding-v3}")
    private String embeddingModel = "text-embedding-v3";

    public RagGoldManualEvaluationRunner(
            RagGoldDatasetReadService datasetReadService,
            RagEvaluationCaseExecutor caseExecutor,
            RagGoldRetrievalCaseExecutor retrievalCaseExecutor,
            RagGoldEvidenceMatcher evidenceMatcher,
            RagGoldManualEvaluationScorer manualScorer,
            RagEvaluationHistoryService historyService,
            RagGoldManualEvaluationPreviousRunLoader previousRunLoader,
            KnowledgeSearchTool knowledgeSearchTool) {
        this.datasetReadService = datasetReadService;
        this.caseExecutor = caseExecutor;
        this.retrievalCaseExecutor = retrievalCaseExecutor;
        this.evidenceMatcher = evidenceMatcher;
        this.manualScorer = manualScorer;
        this.historyService = historyService;
        this.previousRunLoader = previousRunLoader;
        this.knowledgeSearchTool = knowledgeSearchTool;
    }

    /** 按 datasetKey + datasetVersion 加载快照并运行整批评测。 */
    public RagGoldManualEvaluationRunOutcome run(
            UUID workspacePublicId, String datasetKey, String datasetVersion) {
        return run(workspacePublicId, datasetKey, datasetVersion, RagGoldEvaluationRunRequest.endToEnd());
    }

    /** 带运行选项：retrieval-only、case 子集与 embedding 缓存。 */
    public RagGoldManualEvaluationRunOutcome run(
            UUID workspacePublicId,
            String datasetKey,
            String datasetVersion,
            RagGoldEvaluationRunRequest runRequest) {
        RagGoldDatasetSnapshot snapshot = datasetReadService.loadRunnableSnapshot(
                workspacePublicId, datasetKey, datasetVersion);
        return runSnapshot(workspacePublicId, snapshot, null, null, runRequest);
    }

    /** 按 datasetPublicId 加载快照并运行整批评测。 */
    public RagGoldManualEvaluationRunOutcome runByPublicId(
            UUID workspacePublicId, UUID datasetPublicId) {
        RagGoldDatasetSnapshot snapshot = datasetReadService.loadRunnableSnapshotByPublicId(
                workspacePublicId, datasetPublicId);
        return runSnapshot(workspacePublicId, snapshot, null, null, RagGoldEvaluationRunRequest.endToEnd());
    }

    /**
     * 仅重跑上一轮失败题，并与成功题 carry-forward 合并为完整 240 题批次。
     *
     * @param previousRun 上一轮 DB 或 JSON 摘要；数据集 checksum 必须与当前快照一致
     */
    public RagGoldManualEvaluationRunOutcome runRetryFailed(
            UUID workspacePublicId,
            String datasetKey,
            String datasetVersion,
            RagGoldManualEvaluationPreviousRun previousRun) {
        RagGoldDatasetSnapshot snapshot = datasetReadService.loadRunnableSnapshot(
                workspacePublicId, datasetKey, datasetVersion);
        validateRetryDataset(snapshot, previousRun);
        List<String> failedKeys = previousRunLoader.failedCaseKeys(previousRun);
        if (failedKeys.isEmpty()) {
            throw new IllegalArgumentException("上一轮无失败题目，无需重跑: " + previousRun.runPublicId());
        }
        log.info(
                "RAG_GOLD_EVAL retry_failed_from={}, failed_case_count={}",
                previousRun.runPublicId(),
                failedKeys.size());
        return runSnapshot(
                workspacePublicId,
                snapshot,
                Set.copyOf(failedKeys),
                previousRunLoader.indexByCaseKey(previousRun),
                RagGoldEvaluationRunRequest.endToEnd());
    }

    private void validateRetryDataset(
            RagGoldDatasetSnapshot snapshot, RagGoldManualEvaluationPreviousRun previousRun) {
        String currentLabel = snapshot.datasetKey() + "/" + snapshot.datasetVersion() + ":" + snapshot.checksum();
        if (previousRun.datasetVersionLabel() != null
                && !previousRun.datasetVersionLabel().equals(currentLabel)) {
            throw new IllegalArgumentException(
                    "上一轮数据集版本与当前快照不一致，不能合并: previous="
                            + previousRun.datasetVersionLabel()
                            + ", current="
                            + currentLabel);
        }
        if (previousRun.caseResults().size() != snapshot.cases().size()) {
            throw new IllegalArgumentException(
                    "上一轮题数与当前快照不一致: previous="
                            + previousRun.caseResults().size()
                            + ", current="
                            + snapshot.cases().size());
        }
    }

    private RagGoldManualEvaluationRunOutcome runSnapshot(
            UUID workspacePublicId, RagGoldDatasetSnapshot snapshot) {
        return runSnapshot(workspacePublicId, snapshot, null, null, RagGoldEvaluationRunRequest.endToEnd());
    }

    private RagGoldManualEvaluationRunOutcome runSnapshot(
            UUID workspacePublicId,
            RagGoldDatasetSnapshot snapshot,
            Set<String> retryCaseKeys,
            Map<String, RagGoldManualEvaluationCaseResult> carryForwardByKey,
            RagGoldEvaluationRunRequest runRequest) {
        List<RagGoldCaseSnapshot> runnableCases = filterCases(snapshot.cases(), runRequest);
        if (runRequest.limitsCases() && runnableCases.isEmpty()) {
            throw new IllegalArgumentException("case 子集过滤后无题目可运行");
        }
        List<RagGoldEvidenceSnapshot> allEvidences = snapshot.cases().stream()
                .flatMap(goldCase -> goldCase.evidences().stream())
                .toList();
        Map<UUID, Integer> versionNumbers = evidenceMatcher.resolveVersionNumbersFromEvidences(allEvidences);
        boolean frozenSplit = snapshot.split() == RagGoldDatasetSplit.FROZEN;

        boolean retrievalOnly = runRequest.mode() == RagGoldEvaluationRunMode.RETRIEVAL_ONLY;
        String evaluationMode = retrievalOnly ? "retrieval-only" : "end-to-end";
        RagGoldRetrievalExecutionContext retrievalContext = new RagGoldRetrievalExecutionContext(
                snapshot.checksum(),
                embeddingModel,
                runRequest.embeddingCacheDir() == null
                        ? null
                        : new RagGoldEvaluationEmbeddingCache(runRequest.embeddingCacheDir()),
                runRequest.useEmbeddingCache(),
                runRequest.rerankerEnabled(),
                runRequest.identifierSupplementEnabled(),
                runRequest.subQueryQuotaEnabled(),
                runRequest.evidenceGateEnabled(),
                null,
                List.of());

        Map<String, RagEvaluationObservation> observations = new LinkedHashMap<>();
        List<RagGoldManualCaseScore> caseScores = new ArrayList<>();
        List<RagGoldManualCaseExecutionMeta> executionMetas = new ArrayList<>();
        List<RagGoldManualEvaluationCaseResult> caseResults = new ArrayList<>();
        boolean subsetRun = runRequest.limitsCases() && retryCaseKeys == null;
        List<RagGoldCaseSnapshot> casesToRun = subsetRun ? runnableCases : snapshot.cases();
        List<String> caseKeys = casesToRun.stream().map(RagGoldCaseSnapshot::caseKey).toList();

        for (RagGoldCaseSnapshot goldCase : casesToRun) {
            boolean retryThisCase = retryCaseKeys == null || retryCaseKeys.contains(goldCase.caseKey());
            if (!retryThisCase) {
                RagGoldManualEvaluationCaseResult carried = carryForwardByKey.get(goldCase.caseKey());
                if (carried == null) {
                    throw new IllegalStateException("缺少 carry-forward 结果: " + goldCase.caseKey());
                }
                caseScores.add(RagGoldManualEvaluationCarryForwardSupport.toScore(goldCase, carried));
                executionMetas.add(RagGoldManualEvaluationCarryForwardSupport.toExecutionMeta(carried));
                caseResults.add(carried);
                observations.put(
                        goldCase.caseKey(),
                        conservativeObservationFromCarried(goldCase, carried));
                continue;
            }

            log.info("RAG_GOLD_EVAL case_key={}, status=started, mode={}", goldCase.caseKey(), evaluationMode);
            RagEvaluationCaseDefinition definition = toCaseDefinition(goldCase, versionNumbers);
            if (retrievalOnly) {
                executeRetrievalOnlyCase(
                        workspacePublicId,
                        goldCase,
                        definition,
                        versionNumbers,
                        retrievalContext,
                        frozenSplit,
                        caseScores,
                        executionMetas,
                        caseResults,
                        observations);
            } else {
                executeEndToEndCase(
                        workspacePublicId,
                        goldCase,
                        definition,
                        versionNumbers,
                        runRequest,
                        frozenSplit,
                        caseScores,
                        executionMetas,
                        caseResults,
                        observations);
            }
        }

        // legacy 指标
        List<RagGoldEvaluationCase> legacyCases = casesToRun.stream()
                .map(goldCase -> new RagGoldEvaluationCase(
                        goldCase.caseKey(),
                        evidenceMatcher.toLegacyPrefixes(goldCase.evidences(), versionNumbers)))
                .toList();
        RagEvaluationMetrics legacyMetrics = retryCaseKeys == null
                ? new RagGoldEvaluationRunner().run(
                        legacyCases, evaluationCase -> observations.get(evaluationCase.caseId()))
                : RagGoldManualEvaluationCarryForwardSupport.legacyMetricsFromCaseResults(caseResults);

        RagGoldManualRunContext context = new RagGoldManualRunContext(
                snapshot.datasetKey(),
                snapshot.datasetVersion(),
                snapshot.split().name(),
                snapshot.checksum(),
                caseKeys,
                promptTemplate.version(),
                embeddingModel,
                resolveRetrievalVersion(runRequest),
                evaluationMode);
        RagEvaluationMetrics metrics = manualScorer.aggregateWithLegacy(
                legacyMetrics.retrievalRecallRate(),
                legacyMetrics.citationCorrectnessRate(),
                legacyMetrics.ungroundedAnswerRate(),
                caseScores,
                executionMetas,
                context);

        String datasetVersionLabel = snapshot.datasetKey() + "/" + snapshot.datasetVersion() + ":" + snapshot.checksum();
        RagEvaluationRunResult runResult = new RagEvaluationRunResult(
                datasetVersionLabel,
                promptTemplate.version(),
                retrievalOnly ? "retrieval-only" : modelLabelForRun(),
                resolveRetrievalVersion(runRequest),
                metrics,
                caseResults.stream()
                        .map(result -> new RagEvaluationCaseResult(
                                result.caseKey(),
                                result.questionType() == null ? "manual-gold" : result.questionType(),
                                result.status(),
                                result.expectedEvidenceCount() == null ? 0 : result.expectedEvidenceCount(),
                                result.retrievedExpectedEvidenceCount() == null ? 0 : result.retrievedExpectedEvidenceCount(),
                                result.citedEvidenceCount() == null ? 0 : result.citedEvidenceCount(),
                                result.correctCitationCount() == null ? 0 : result.correctCitationCount(),
                                Boolean.TRUE.equals(result.ungrounded())))
                        .toList());

        var persisted = historyService.recordManual(workspacePublicId, runResult, caseResults);
        return new RagGoldManualEvaluationRunOutcome(
                persisted.getPublicId(),
                runResult,
                caseResults,
                metrics.extended().failedCaseCount() > 0,
                frozenSplit);
    }

    private List<RagGoldCaseSnapshot> filterCases(
            List<RagGoldCaseSnapshot> cases, RagGoldEvaluationRunRequest runRequest) {
        if (!runRequest.limitsCases()) {
            return cases;
        }
        return cases.stream()
                .filter(goldCase -> runRequest.caseKeysFilter().contains(goldCase.caseKey()))
                .toList();
    }

    private void executeEndToEndCase(
            UUID workspacePublicId,
            RagGoldCaseSnapshot goldCase,
            RagEvaluationCaseDefinition definition,
            Map<UUID, Integer> versionNumbers,
            RagGoldEvaluationRunRequest runRequest,
            boolean frozenSplit,
            List<RagGoldManualCaseScore> caseScores,
            List<RagGoldManualCaseExecutionMeta> executionMetas,
            List<RagGoldManualEvaluationCaseResult> caseResults,
            Map<String, RagEvaluationObservation> observations) {
        RagEvaluationCaseExecution execution = caseExecutor.execute(
                workspacePublicId,
                definition,
                runRequest.rerankerEnabled(),
                runRequest.identifierSupplementEnabled(),
                runRequest.subQueryQuotaEnabled());
        RagEvaluationObservation observation = "succeeded".equals(execution.status())
                ? observation(execution.retrieval(), execution.answer())
                : conservativeObservation(goldCase);
        observations.put(goldCase.caseKey(), observation);

        List<String> rankedIds = execution.retrieval().evidence().stream()
                .map(item -> item.id())
                .toList();
        List<String> candidateRankedIds = execution.retrievalDiagnostics() == null
                ? List.of()
                : execution.retrievalDiagnostics().candidates().stream()
                        .map(candidate -> "knowledge:" + candidate.documentId() + ":v" + candidate.versionNo()
                                + ":" + candidate.chunkId())
                        .toList();
        RagGoldCaseRetrievalFunnel sourceCounts = execution.retrievalDiagnostics() == null
                ? null
                : new RagGoldCaseRetrievalFunnel(
                        false, false, false, false, false, false, false,
                        execution.retrievalDiagnostics().lexicalOnlyChunkIds().size(),
                        execution.retrievalDiagnostics().vectorOnlyChunkIds().size(),
                        execution.retrievalDiagnostics().bothSourceChunkIds().size());
        RagGoldRetrievalCaseDiagnostics retrievalDiagnostics = execution.retrievalDiagnostics() == null
                ? null
                : RagGoldRetrievalDiagnosticsComputer.compute(
                        execution.retrievalDiagnostics(),
                        goldCase.evidences(),
                        goldCase.questionType(),
                        versionNumbers,
                        evidenceMatcher);
        RagGoldManualCaseScore caseScore = manualScorer.scoreCase(
                goldCase,
                rankedIds,
                candidateRankedIds,
                sourceCounts,
                observation,
                execution.answer(),
                versionNumbers,
                retrievalDiagnostics);
        caseScores.add(caseScore);
        executionMetas.add(new RagGoldManualCaseExecutionMeta(
                goldCase.caseKey(),
                execution.status(),
                execution.failureStage(),
                execution.retrievalLatencyMs(),
                execution.generationLatencyMs(),
                execution.totalLatencyMs(),
                execution.promptTokens(),
                execution.completionTokens(),
                execution.totalTokens()));
        caseResults.add(toCaseResult(goldCase, caseScore, execution, frozenSplit, versionNumbers, retrievalDiagnostics));
        logCaseCompletion(goldCase.caseKey(), execution, frozenSplit);
    }

    private void executeRetrievalOnlyCase(
            UUID workspacePublicId,
            RagGoldCaseSnapshot goldCase,
            RagEvaluationCaseDefinition definition,
            Map<UUID, Integer> versionNumbers,
            RagGoldRetrievalExecutionContext retrievalContext,
            boolean frozenSplit,
            List<RagGoldManualCaseScore> caseScores,
            List<RagGoldManualCaseExecutionMeta> executionMetas,
            List<RagGoldManualEvaluationCaseResult> caseResults,
            Map<String, RagEvaluationObservation> observations) {
        RagGoldRetrievalCaseExecution retrievalExecution = retrievalCaseExecutor.execute(
                workspacePublicId,
                definition,
                retrievalContextForCase(goldCase, retrievalContext));
        KnowledgeRetrievalDiagnostics diagnostics = retrievalExecution.diagnostics();
        KnowledgeRetrievalResult retrieval = diagnostics == null
                ? new KnowledgeRetrievalResult(0, List.of())
                : diagnostics.result();
        RagEvaluationObservation observation = "succeeded".equals(retrievalExecution.status())
                ? retrievalOnlyObservation(retrieval)
                : conservativeObservation(goldCase);
        observations.put(goldCase.caseKey(), observation);

        List<String> finalRankedIds = retrieval.evidence().stream().map(item -> item.id()).toList();
        List<String> candidateRankedIds = diagnostics == null
                ? List.of()
                : diagnostics.candidates().stream()
                        .map(candidate -> "knowledge:" + candidate.documentId() + ":v" + candidate.versionNo()
                                + ":" + candidate.chunkId())
                        .toList();
        RagGoldCaseRetrievalFunnel sourceCounts = diagnostics == null
                ? null
                : new RagGoldCaseRetrievalFunnel(
                        false, false, false, false, false, false, false,
                        diagnostics.lexicalOnlyChunkIds().size(),
                        diagnostics.vectorOnlyChunkIds().size(),
                        diagnostics.bothSourceChunkIds().size());
        RagGoldRetrievalCaseDiagnostics retrievalDiagnostics = diagnostics == null
                ? null
                : RagGoldRetrievalDiagnosticsComputer.compute(
                        diagnostics,
                        goldCase.evidences(),
                        goldCase.questionType(),
                        versionNumbers,
                        evidenceMatcher);
        RagGoldManualCaseScore caseScore = manualScorer.scoreCase(
                goldCase,
                finalRankedIds,
                candidateRankedIds,
                sourceCounts,
                observation,
                "",
                versionNumbers,
                retrievalDiagnostics);
        caseScores.add(caseScore);
        executionMetas.add(new RagGoldManualCaseExecutionMeta(
                goldCase.caseKey(),
                retrievalExecution.status(),
                retrievalExecution.failureStage(),
                retrievalExecution.retrievalLatencyMs(),
                null,
                retrievalExecution.totalLatencyMs(),
                null,
                null,
                null));
        RagEvaluationCaseExecution pseudoExecution = new RagEvaluationCaseExecution(
                retrieval,
                "",
                retrievalExecution.status(),
                retrievalExecution.failureStage(),
                retrievalExecution.retrievalLatencyMs(),
                0L,
                retrievalExecution.totalLatencyMs(),
                diagnostics);
        caseResults.add(toCaseResult(goldCase, caseScore, pseudoExecution, frozenSplit, versionNumbers, retrievalDiagnostics));
        logRetrievalCaseCompletion(goldCase.caseKey(), retrievalExecution, frozenSplit);
    }

    private RagEvaluationObservation retrievalOnlyObservation(KnowledgeRetrievalResult retrieval) {
        Set<String> retrieved = retrieval.evidence().stream()
                .map(item -> item.id())
                .collect(Collectors.toSet());
        return new RagEvaluationObservation(retrieved, Set.of(), false);
    }

    private RagGoldRetrievalExecutionContext retrievalContextForCase(
            RagGoldCaseSnapshot goldCase, RagGoldRetrievalExecutionContext base) {
        return new RagGoldRetrievalExecutionContext(
                base.datasetChecksum(),
                base.embeddingModel(),
                base.embeddingCache(),
                base.useEmbeddingCache(),
                base.rerankerEnabled(),
                base.identifierSupplementEnabled(),
                base.subQueryQuotaEnabled(),
                base.evidenceGateEnabled(),
                goldCase.questionType(),
                goldCase.evidences());
    }

    private RagEvaluationCaseDefinition toCaseDefinition(
            RagGoldCaseSnapshot goldCase, Map<UUID, Integer> versionNumbers) {
        Set<String> prefixes = evidenceMatcher.toLegacyPrefixes(goldCase.evidences(), versionNumbers);
        List<ContextTurn> contextTurns = goldCase.contextTurns() == null
                ? List.of()
                : goldCase.contextTurns().stream()
                        .map(turn -> new ContextTurn(turn.role(), turn.content()))
                        .toList();
        return new RagEvaluationCaseDefinition(
                goldCase.caseKey(),
                goldCase.questionType().name().toLowerCase(),
                goldCase.questionText(),
                prefixes,
                contextTurns);
    }

    private RagEvaluationObservation conservativeObservation(RagGoldCaseSnapshot goldCase) {
        boolean shouldClaim = !goldCase.shouldRefuse() && !goldCase.evidences().isEmpty();
        return new RagEvaluationObservation(Set.of(), Set.of(), shouldClaim);
    }

    /** carry-forward 题用于 legacy 聚合的保守观测值。 */
    private RagEvaluationObservation conservativeObservationFromCarried(
            RagGoldCaseSnapshot goldCase, RagGoldManualEvaluationCaseResult carried) {
        boolean shouldClaim = !goldCase.shouldRefuse()
                && !goldCase.evidences().isEmpty()
                && !Boolean.TRUE.equals(carried.ungrounded());
        return new RagEvaluationObservation(Set.of(), Set.of(), shouldClaim);
    }

    private RagEvaluationObservation observation(KnowledgeRetrievalResult retrieval, String answer) {
        Set<String> retrieved = retrieval.evidence().stream()
                .map(item -> item.id())
                .collect(Collectors.toSet());
        Set<String> cited = citations(answer == null ? "" : answer);
        boolean containsKnowledgeClaim = answer == null || !answer.contains("未检索到已发布企业知识");
        return new RagEvaluationObservation(retrieved, cited, containsKnowledgeClaim);
    }

    private Set<String> citations(String answer) {
        Matcher matcher = KNOWLEDGE_CITATION.matcher(answer);
        Set<String> result = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return Set.copyOf(result);
    }

    private RagGoldManualEvaluationCaseResult toCaseResult(
            RagGoldCaseSnapshot goldCase,
            RagGoldManualCaseScore score,
            RagEvaluationCaseExecution execution,
            boolean frozenSplit,
            Map<UUID, Integer> versionNumbers,
            RagGoldRetrievalCaseDiagnostics retrievalDiagnostics) {
        if (frozenSplit) {
            return RagGoldManualEvaluationCaseResult.frozenRedacted(
                    goldCase.caseKey(),
                    execution.status(),
                    execution.failureStage(),
                    toErrorCode(execution.failureStage()));
        }
        int expectedCount = goldCase.evidences().size();
        int retrievedExpected = (int) goldCase.evidences().stream()
                .filter(evidence -> score.observation().retrievedEvidenceIds().stream()
                        .anyMatch(id -> evidenceMatcher.matchesEvidence(evidence, id, versionNumbers)))
                .count();
        return buildDetailedCaseResult(
                goldCase, score, execution, expectedCount, retrievedExpected, retrievalDiagnostics);
    }

    private RagGoldManualEvaluationCaseResult buildDetailedCaseResult(
            RagGoldCaseSnapshot goldCase,
            RagGoldManualCaseScore score,
            RagEvaluationCaseExecution execution,
            int expectedCount,
            int retrievedExpected,
            RagGoldRetrievalCaseDiagnostics retrievalDiagnostics) {
        int citedCount = score.observation().citedEvidenceIds().size();
        int correctCitation = (int) score.observation().citedEvidenceIds().stream()
                .filter(score.observation().retrievedEvidenceIds()::contains)
                .count();
        boolean ungrounded = score.observation().containsKnowledgeClaim()
                && (citedCount == 0 || correctCitation != citedCount);
        double factCoverage = score.requiredFacts() == 0
                ? 1.0
                : (double) score.coveredFacts() / score.requiredFacts();
        double forbiddenHit = score.forbiddenClaims() == 0
                ? 0.0
                : (double) score.hitForbiddenClaims() / score.forbiddenClaims();
        return new RagGoldManualEvaluationCaseResult(
                goldCase.caseKey(),
                execution.status(),
                execution.failureStage(),
                toErrorCode(execution.failureStage()),
                goldCase.questionType().name(),
                expectedCount,
                retrievedExpected,
                citedCount,
                correctCitation,
                ungrounded,
                factCoverage,
                forbiddenHit,
                score.refusalCompliant(),
                score.documentHitAt1(),
                score.documentHitAt3(),
                score.documentHitAt8(),
                score.chunkHitAt1(),
                score.chunkHitAt3(),
                score.chunkHitAt8(),
                score.reciprocalRank(),
                score.ndcgAt8(),
                execution.retrievalLatencyMs(),
                execution.generationLatencyMs(),
                execution.totalLatencyMs(),
                retrievalDiagnostics);
    }

    private String toErrorCode(String failureStage) {
        if (failureStage == null || failureStage.isBlank()) {
            return null;
        }
        return failureStage;
    }

    /** 批次元数据记录主模型；启用备用模型时追加 fallback 标签，便于对比混合批次。 */
    private String modelLabelForRun() {
        if (fallbackChatModel == null || fallbackChatModel.isBlank()) {
            return configuredModelName;
        }
        return configuredModelName + "+fallback:" + fallbackChatModel;
    }

    private void logRetrievalCaseCompletion(
            String caseKey, RagGoldRetrievalCaseExecution execution, boolean frozenSplit) {
        if (frozenSplit) {
            log.info(
                    "RAG_GOLD_EVAL case_key={}, status={}, failure_stage={}, error_code={}",
                    caseKey,
                    execution.status(),
                    execution.failureStage(),
                    toErrorCode(execution.failureStage()));
        } else {
            log.info(
                    "RAG_GOLD_EVAL case_key={}, status={}, failure_stage={}, retrieval_latency_ms={}, total_latency_ms={}, mode=retrieval-only",
                    caseKey,
                    execution.status(),
                    execution.failureStage(),
                    execution.retrievalLatencyMs(),
                    execution.totalLatencyMs());
        }
    }

    private void logCaseCompletion(String caseKey, RagEvaluationCaseExecution execution, boolean frozenSplit) {
        if (frozenSplit) {
            log.info(
                    "RAG_GOLD_EVAL case_key={}, status={}, failure_stage={}, error_code={}",
                    caseKey,
                    execution.status(),
                    execution.failureStage(),
                    toErrorCode(execution.failureStage()));
        } else {
            log.info(
                    "RAG_GOLD_EVAL case_key={}, status={}, failure_stage={}, retrieval_latency_ms={}, generation_latency_ms={}, total_latency_ms={}, prompt_tokens={}, completion_tokens={}, total_tokens={}",
                    caseKey,
                    execution.status(),
                    execution.failureStage(),
                    execution.retrievalLatencyMs(),
                    execution.generationLatencyMs(),
                    execution.totalLatencyMs(),
                    formatTokenCount(execution.promptTokens()),
                    formatTokenCount(execution.completionTokens()),
                    formatTokenCount(execution.totalTokens()));
        }
    }

    private String formatTokenCount(Long tokens) {
        return tokens == null ? "unavailable" : String.valueOf(tokens);
    }

    private String resolveRetrievalVersion(RagGoldEvaluationRunRequest runRequest) {
        boolean rerankerEnabled = runRequest != null && runRequest.rerankerEnabled();
        boolean identifierEnabled = runRequest == null || runRequest.identifierSupplementEnabled();
        boolean subquotaEnabled = runRequest == null || runRequest.subQueryQuotaEnabled();
        boolean evidenceGateEnabled = runRequest == null || runRequest.evidenceGateEnabled();
        return knowledgeSearchTool.resolveRetrievalVersionLabel(
                KnowledgeRetrievalOptions.withDecomposition(
                        rerankerEnabled, null, null, identifierEnabled, subquotaEnabled, evidenceGateEnabled));
    }
}
