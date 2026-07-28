package com.insightflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.insightflow.entity.EvaluationRun;
import com.insightflow.evaluation.EvaluationCaseDelta;
import com.insightflow.evaluation.GoldEvaluationMetrics;
import com.insightflow.evaluation.GoldEvaluationRunResult;
import com.insightflow.evaluation.GoldEvaluationRunner;
import com.insightflow.evaluation.EvaluationRegressionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.insightflow.entity.RagEvaluationRun;
import com.insightflow.evaluation.rag.RagEvaluationMetrics;
import com.insightflow.evaluation.rag.RagEvaluationTaskCommandService;
import com.insightflow.evaluation.rag.RagEvaluationTaskQueryService;
import com.insightflow.service.EvaluationHistoryService;
import com.insightflow.service.RagEvaluationHistoryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 金标评测 API 契约测试：触发前必须先校验工作区，避免无归属的评测访问入口。
 */
@ExtendWith(MockitoExtension.class)
class EvaluationControllerTest {

    @Mock
    private GoldEvaluationRunner evaluationRunner;

    @Mock
    private EvaluationHistoryService evaluationHistoryService;

    @Mock
    private RagEvaluationTaskCommandService ragEvaluationTaskCommandService;
    @Mock
    private RagEvaluationTaskQueryService ragEvaluationTaskQueryService;

    @Mock
    private RagEvaluationHistoryService ragEvaluationHistoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 完整批次运行后应由历史服务完成工作区校验并返回公开批次 ID，Controller 不暴露实体内部主键。
     */
    @Test
    void runsGoldEvaluationAfterValidatingWorkspace() {
        UUID workspaceId = UUID.randomUUID();
        GoldEvaluationRunResult expected = new GoldEvaluationRunResult(
                "gold:v1", "chat:v1", "qwen-test", List.of(),
                new GoldEvaluationMetrics(
                        0, 0, 0, 0, 0, null, 0,
                        null, null, null, null, null, null, null, null, null, 0.0));
        EvaluationRun persisted = EvaluationRun.create(
                7L, "gold:v1", "chat:v1", "qwen-test", "none", "{}", "[]");
        when(evaluationRunner.run()).thenReturn(expected);
        when(evaluationHistoryService.record(workspaceId, expected)).thenReturn(persisted);

        EvaluationController.GoldRunResponse actual = new EvaluationController(
                evaluationRunner, evaluationHistoryService, ragEvaluationHistoryService,
                ragEvaluationTaskCommandService, ragEvaluationTaskQueryService, objectMapper)
                .runGoldEvaluation(workspaceId);

        assertThat(actual.runId()).isEqualTo(persisted.getPublicId());
        assertThat(actual.result()).isSameAs(expected);
    }

    /**
     * 比较接口只暴露公开批次标识、版本和门禁结论，实体内部 id 与 workspace_id 不得进入响应。
     */
    @Test
    void comparesTwoPublicEvaluationRuns() {
        UUID workspaceId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        EvaluationRun candidate = EvaluationRun.create(
                7L, "gold:v1", "chat:v2", "qwen-test", "none", "{}", "[]");
        EvaluationRun baseline = EvaluationRun.create(
                7L, "gold:v1", "chat:v1", "qwen-test", "none", "{}", "[]");
        GoldEvaluationMetrics candidateMetrics = new GoldEvaluationMetrics(
                1, 1, 0, 0.8, 0.1, null, 100L,
                10L, 20L, 30L, 90L, 100L, 10L, 11L, 20L, 21L, 0.8);
        GoldEvaluationMetrics baselineMetrics = new GoldEvaluationMetrics(
                1, 1, 0, 0.9, 0.0, null, 80L,
                8L, 18L, 26L, 70L, 80L, 8L, 9L, 18L, 19L, 0.9);
        EvaluationCaseDelta delta = new EvaluationCaseDelta(
                "trend-001", "trend", "regressed", -1, 1, null, null);
        EvaluationHistoryService.Comparison comparison = new EvaluationHistoryService.Comparison(
                candidate,
                baseline,
                candidateMetrics,
                baselineMetrics,
                List.of(delta),
                new EvaluationRegressionGate.Comparison(true, List.of()));
        when(evaluationHistoryService.compare(workspaceId, candidateId, baselineId)).thenReturn(comparison);

        EvaluationController.ComparisonResponse response = new EvaluationController(
                evaluationRunner, evaluationHistoryService, ragEvaluationHistoryService,
                ragEvaluationTaskCommandService, ragEvaluationTaskQueryService, objectMapper)
                .compareGoldEvaluation(workspaceId, candidateId, baselineId);

        assertThat(response.candidateRunId()).isEqualTo(candidate.getPublicId());
        assertThat(response.baselineRunId()).isEqualTo(baseline.getPublicId());
        assertThat(response.candidatePromptVersion()).isEqualTo("chat:v2");
        assertThat(response.baselinePromptVersion()).isEqualTo("chat:v1");
        assertThat(response.candidateMetrics()).isEqualTo(candidateMetrics);
        assertThat(response.baselineMetrics()).isEqualTo(baselineMetrics);
        assertThat(response.caseDeltas()).containsExactly(delta);
        assertThat(response.passed()).isTrue();
    }

    /**
     * 历史列表只返回选择基线所需的批次摘要，不把逐题输出和实体内部主键暴露给调用方。
     */
    @Test
    void listsGoldEvaluationRunSummaries() {
        UUID workspaceId = UUID.randomUUID();
        EvaluationRun run = EvaluationRun.create(
                7L, "gold:v1", "chat:v1", "qwen-test", "none", "{}", "[]");
        when(evaluationHistoryService.listRecent(workspaceId)).thenReturn(List.of(run));

        List<EvaluationController.RunSummaryResponse> response = new EvaluationController(
                evaluationRunner, evaluationHistoryService, ragEvaluationHistoryService,
                ragEvaluationTaskCommandService, ragEvaluationTaskQueryService, objectMapper)
                .listGoldEvaluationRuns(workspaceId);

        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.runId()).isEqualTo(run.getPublicId());
            assertThat(item.datasetVersion()).isEqualTo("gold:v1");
            assertThat(item.promptVersion()).isEqualTo("chat:v1");
            assertThat(item.modelName()).isEqualTo("qwen-test");
        });
    }

    /**
     * RAG 历史列表必须返回三项脱敏指标，否则页面只能看到版本和时间而无法判断质量基线。
     */
    @Test
    void listsRagEvaluationRunSummariesWithMetrics() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        RagEvaluationRun run = RagEvaluationRun.create(
                7L, "rag-gold:v1:abc", "chat:v4", "qwen-test", "knowledge:rrf:v1",
                objectMapper.writeValueAsString(new RagEvaluationMetrics(0.8, 1.0, 0.2, 5)),
                "[]");
        when(ragEvaluationHistoryService.listRecent(workspaceId)).thenReturn(List.of(run));

        List<EvaluationController.RagRunSummaryResponse> response = new EvaluationController(
                evaluationRunner, evaluationHistoryService, ragEvaluationHistoryService,
                ragEvaluationTaskCommandService, ragEvaluationTaskQueryService, objectMapper)
                .listRagEvaluationRuns(workspaceId);

        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.runId()).isEqualTo(run.getPublicId());
            assertThat(item.metrics().retrievalRecallRate()).isEqualTo(0.8);
            assertThat(item.metrics().citationCorrectnessRate()).isEqualTo(1.0);
            assertThat(item.metrics().ungroundedAnswerRate()).isEqualTo(0.2);
        });
    }

    /** 列表 JSON 必须携带 metrics 对象，供评测页直接渲染三项 RAG 指标。 */
    @Test
    void serializesRagRunSummaryWithMetricsField() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RagEvaluationRun run = RagEvaluationRun.create(
                7L, "rag-gold:v1:abc", "chat:v4", "qwen-test", "knowledge:rrf:v1",
                mapper.writeValueAsString(new RagEvaluationMetrics(1.0, 1.0, 0.2, 5)),
                "[]");
        String json = mapper.writeValueAsString(
                EvaluationController.RagRunSummaryResponse.from(run, mapper));
        assertThat(json).contains("\"metrics\"");
        assertThat(json).contains("retrievalRecallRate");
    }
}
