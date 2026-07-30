package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.entity.RagEvaluationRun;
import com.insightflow.entity.RagGoldDatasetSplit;
import com.insightflow.entity.RagGoldDatasetStatus;
import com.insightflow.entity.RagGoldAssertionType;
import com.insightflow.entity.RagGoldDifficulty;
import com.insightflow.entity.RagGoldEvidenceGranularity;
import com.insightflow.entity.RagGoldQuestionType;
import com.insightflow.evaluation.rag.gold.RagGoldAssertionSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldCaseSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldDatasetReadService;
import com.insightflow.evaluation.rag.gold.RagGoldDatasetSnapshot;
import com.insightflow.evaluation.rag.gold.RagGoldEvidenceSnapshot;
import com.insightflow.knowledge.KnowledgeEvidence;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import com.insightflow.knowledge.KnowledgeSearchTool;
import com.insightflow.service.RagEvaluationHistoryService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runner 应加载快照、逐题执行并在单题失败时继续批次。
 */
class RagGoldManualEvaluationRunnerTest {

    private static final UUID WORKSPACE = UUID.fromString("1f1898d9-8b54-6fe3-88fa-9b6f9cb0d668");
    private static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID VER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CHUNK = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private RagGoldDatasetReadService readService;
    private RagEvaluationCaseExecutor caseExecutor;
    private RagGoldManualEvaluationRunner runner;

    @BeforeEach
    void setUp() {
        readService = mock(RagGoldDatasetReadService.class);
        caseExecutor = mock(RagEvaluationCaseExecutor.class);
        RagGoldEvidenceMatcher evidenceMatcher = mock(RagGoldEvidenceMatcher.class);
        when(evidenceMatcher.resolveVersionNumbersFromEvidences(any())).thenReturn(java.util.Map.of(VER, 2));
        when(evidenceMatcher.toLegacyPrefixes(any(), any())).thenReturn(Set.of("knowledge:" + DOC + ":"));
        when(evidenceMatcher.matchesEvidence(any(), any(), any())).thenReturn(true);
        RagGoldManualEvaluationScorer scorer = new RagGoldManualEvaluationScorer(evidenceMatcher);
        RagEvaluationHistoryService historyService = mock(RagEvaluationHistoryService.class);
        RagEvaluationRun persisted = mock(RagEvaluationRun.class);
        when(persisted.getPublicId()).thenReturn(UUID.randomUUID());
        when(historyService.recordManual(any(), any(), any())).thenReturn(persisted);
        RagGoldManualEvaluationPreviousRunLoader previousRunLoader = mock(RagGoldManualEvaluationPreviousRunLoader.class);
        RagGoldRetrievalCaseExecutor retrievalCaseExecutor = mock(RagGoldRetrievalCaseExecutor.class);
        KnowledgeSearchTool knowledgeSearchTool = mock(KnowledgeSearchTool.class);
        when(knowledgeSearchTool.resolveRetrievalVersionLabel(any())).thenReturn("knowledge:rrf:v3");
        runner = new RagGoldManualEvaluationRunner(
                readService, caseExecutor, retrievalCaseExecutor, evidenceMatcher, scorer, historyService, previousRunLoader, knowledgeSearchTool);
    }

    @Test
    void runsAllCasesAndContinuesAfterFailure() {
        RagGoldDatasetSnapshot snapshot = minimalSnapshot(RagGoldDatasetSplit.DEVELOPMENT);
        when(readService.loadRunnableSnapshot(WORKSPACE, "ops-rag-v1", "dev-240")).thenReturn(snapshot);
        KnowledgeRetrievalResult retrieval = new KnowledgeRetrievalResult(1, List.of(
                new KnowledgeEvidence("knowledge:" + DOC + ":v2:" + CHUNK, "title", 2, "snippet", "/src")));
        when(caseExecutor.execute(eq(WORKSPACE), any(), anyBoolean())).thenReturn(new RagEvaluationCaseExecution(
                        retrieval, "[证据: knowledge:" + DOC + ":v2:" + CHUNK + "]", "succeeded", null, 5, 6, 11))
                .thenReturn(RagEvaluationCaseExecution.failed("retrieval_timeout", 10, 0, 10));

        RagGoldManualEvaluationRunOutcome outcome = runner.run(WORKSPACE, "ops-rag-v1", "dev-240");

        assertThat(outcome.manualCaseResults()).hasSize(2);
        assertThat(outcome.hasPartialFailures()).isTrue();
        assertThat(outcome.runResult().metrics().extended()).isNotNull();
        assertThat(outcome.runResult().metrics().extended().failedCaseCount()).isEqualTo(1);
    }

    @Test
    void redactsFrozenSplitCaseResults() {
        RagGoldDatasetSnapshot snapshot = minimalSnapshot(RagGoldDatasetSplit.FROZEN);
        when(readService.loadRunnableSnapshot(WORKSPACE, "ops-rag-v1", "frozen-80")).thenReturn(snapshot);
        KnowledgeRetrievalResult retrieval = new KnowledgeRetrievalResult(1, List.of(
                new KnowledgeEvidence("knowledge:" + DOC + ":v2:" + CHUNK, "title", 2, "snippet", "/src")));
        when(caseExecutor.execute(eq(WORKSPACE), any(), anyBoolean())).thenReturn(new RagEvaluationCaseExecution(
                retrieval, "secret answer text", "succeeded", null, 5, 6, 11));

        RagGoldManualEvaluationRunOutcome outcome = runner.run(WORKSPACE, "ops-rag-v1", "frozen-80");

        assertThat(outcome.frozenSplit()).isTrue();
        RagGoldManualEvaluationCaseResult redacted = outcome.manualCaseResults().get(0);
        assertThat(redacted.caseKey()).isEqualTo("case-1");
        assertThat(redacted.status()).isEqualTo("succeeded");
        assertThat(redacted.questionType()).isNull();
        assertThat(redacted.expectedEvidenceCount()).isNull();
        assertThat(redacted.requiredFactCoverageRate()).isNull();
    }

    private RagGoldDatasetSnapshot minimalSnapshot(RagGoldDatasetSplit split) {
        RagGoldCaseSnapshot caseOne = new RagGoldCaseSnapshot(
                UUID.randomUUID(), "case-1", "问题一", RagGoldQuestionType.SINGLE_DOCUMENT_FACT,
                RagGoldDifficulty.EASY, false, "basis", "reviewer",
                List.of(new RagGoldEvidenceSnapshot(RagGoldEvidenceGranularity.CHUNK, DOC, VER, CHUNK)),
                List.of(new RagGoldAssertionSnapshot(RagGoldAssertionType.REQUIRED_FACT, "关键事实", 1.0)));
        RagGoldCaseSnapshot caseTwo = new RagGoldCaseSnapshot(
                UUID.randomUUID(), "case-2", "问题二", RagGoldQuestionType.REFUSAL,
                RagGoldDifficulty.EASY, true, "basis", "reviewer", List.of(), List.of());
        return new RagGoldDatasetSnapshot(
                UUID.randomUUID(), "ops-rag-v1", split == RagGoldDatasetSplit.FROZEN ? "frozen-80" : "dev-240",
                split, RagGoldDatasetStatus.PUBLISHED, "corpus:v1", "checksum-abc",
                OffsetDateTime.now(), split == RagGoldDatasetSplit.FROZEN ? OffsetDateTime.now() : null,
                List.of(caseOne, caseTwo));
    }
}
