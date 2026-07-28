package com.insightflow.evaluation.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightflow.knowledge.KnowledgeEvidence;
import com.insightflow.knowledge.KnowledgeRetrievalResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 逐题执行失败必须收敛为该题结果，后续题目仍按同一份受控题集继续评分。
 */
class RagLiveEvaluationRunnerTest {

    @Test
    void keepsScoringLaterCasesAfterOneCaseTimesOut() {
        UUID workspaceId = UUID.randomUUID();
        RagEvaluationCaseDefinition release = new RagEvaluationCaseDefinition(
                "release-note", "release-note", "版本公告有哪些变更？", Set.of("knowledge:document-a:"));
        RagEvaluationCaseDefinition noKnowledge = new RagEvaluationCaseDefinition(
                "no-knowledge", "no-knowledge", "虚构问题如何处理？", Set.of());
        RagEvaluationFixture fixture = new RagEvaluationFixture("rag-gold:v1:abc", List.of(release, noKnowledge));
        RagEvaluationFixtureFactory fixtures = mock(RagEvaluationFixtureFactory.class);
        RagEvaluationCaseExecutor caseExecutor = mock(RagEvaluationCaseExecutor.class);
        KnowledgeRetrievalResult releaseRetrieval = new KnowledgeRetrievalResult(1, List.of(
                new KnowledgeEvidence("knowledge:document-a:v1:chunk-a", "版本公告", 1, "变更说明", "/source")));
        when(fixtures.create(workspaceId)).thenReturn(fixture);
        when(caseExecutor.execute(workspaceId, release)).thenReturn(new RagEvaluationCaseExecution(
                releaseRetrieval, "[证据: knowledge:document-a:v1:chunk-a]", "succeeded", null, 3, 4, 7));
        when(caseExecutor.execute(workspaceId, noKnowledge)).thenReturn(RagEvaluationCaseExecution.failed(
                "retrieval_timeout", 10, 0, 10));

        RagEvaluationRunResult result = new RagLiveEvaluationRunner(fixtures, caseExecutor).run(workspaceId);

        assertThat(result.datasetVersion()).isEqualTo("rag-gold:v1:abc");
        assertThat(result.metrics().retrievalRecallRate()).isEqualTo(1.0);
        assertThat(result.metrics().citationCorrectnessRate()).isEqualTo(1.0);
        assertThat(result.metrics().ungroundedAnswerRate()).isEqualTo(0.0);
        assertThat(result.caseResults()).hasSize(2);
        assertThat(result.caseResults()).extracting(RagEvaluationCaseResult::status)
                .containsExactly("succeeded", "failed");
    }
}
