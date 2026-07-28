package com.insightflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.EvaluationRun;
import com.insightflow.evaluation.EvaluationCaseRunResult;
import com.insightflow.evaluation.EvaluationCaseScore;
import com.insightflow.evaluation.GoldEvaluationMetrics;
import com.insightflow.evaluation.GoldEvaluationRunResult;
import com.insightflow.repository.EvaluationRunRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 评测历史用例测试：批次必须绑定工作区并保存版本化结果快照，不能依赖前端临时缓存。
 */
class EvaluationHistoryServiceTest {

    /**
     * 记录固定金标运行时，应使用服务端解析出的内部工作区键并序列化指标与逐题结果。
     */
    @Test
    void recordsGoldRunInValidatedWorkspace() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        EvaluationRunRepository repository = mock(EvaluationRunRepository.class);
        UUID workspacePublicId = UUID.randomUUID();
        com.insightflow.entity.Workspace workspace = mock(com.insightflow.entity.Workspace.class);
        GoldEvaluationRunResult result = new GoldEvaluationRunResult(
                "gold:v1", "chat:v1", "qwen-test", List.of(),
                new GoldEvaluationMetrics(
                        1, 1, 0, 1.0, 0.0, null, 10L,
                        12L, 34L, 46L, 10L, 10L, 12L, 12L, 34L, 34L, 1.0));
        when(workspace.getId()).thenReturn(7L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(repository.save(any(EvaluationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EvaluationRun saved = new EvaluationHistoryService(workspaceService, repository, new ObjectMapper())
                .record(workspacePublicId, result);

        verify(workspaceService).get(workspacePublicId);
        verify(repository).save(any(EvaluationRun.class));
        assertThat(saved.getWorkspaceId()).isEqualTo(7L);
        assertThat(saved.getDatasetVersion()).isEqualTo("gold:v1");
        assertThat(saved.getPromptVersion()).isEqualTo("chat:v1");
        assertThat(saved.getModelName()).isEqualTo("qwen-test");
        assertThat(saved.getMetricsJson()).contains("factCoverageRate");
        assertThat(saved.getCaseResultsJson()).isEqualTo("[]");
    }

    /**
     * 基线和候选必须通过同一工作区读取，服务应反序列化持久化指标后返回质量门禁结果。
     */
    @Test
    void comparesPersistedRunsInsideSameWorkspace() throws Exception {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        EvaluationRunRepository repository = mock(EvaluationRunRepository.class);
        UUID workspacePublicId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        com.insightflow.entity.Workspace workspace = mock(com.insightflow.entity.Workspace.class);
        ObjectMapper objectMapper = new ObjectMapper();
        GoldEvaluationMetrics baselineMetrics = new GoldEvaluationMetrics(
                10, 10, 0, 0.80, 0.10, 0.80, 100L,
                100L, 100L, 200L, 10L, 20L, 10L, 20L, 10L, 20L, 0.80);
        GoldEvaluationMetrics candidateMetrics = new GoldEvaluationMetrics(
                10, 10, 0, 0.79, 0.11, 0.79, 110L,
                110L, 110L, 220L, 11L, 22L, 11L, 22L, 11L, 22L, 0.79);
        EvaluationRun baseline = EvaluationRun.create(
                7L, "gold:v1", "chat:v1", "qwen-test", "none",
                objectMapper.writeValueAsString(baselineMetrics), objectMapper.writeValueAsString(List.of(
                        new EvaluationCaseRunResult(
                                "trend-001", "trend", "succeeded",
                                new EvaluationCaseScore(2, 2, 1, 0, true, true),
                                "基线回答", 10L, 10L, 20L, 30L, null))));
        EvaluationRun candidate = EvaluationRun.create(
                7L, "gold:v1", "chat:v2", "qwen-test", "none",
                objectMapper.writeValueAsString(candidateMetrics), objectMapper.writeValueAsString(List.of(
                        new EvaluationCaseRunResult(
                                "trend-001", "trend", "succeeded",
                                new EvaluationCaseScore(2, 1, 1, 1, false, true),
                                "候选回答", 11L, 11L, 21L, 32L, null))));
        when(workspace.getId()).thenReturn(7L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(repository.findByPublicIdAndWorkspaceId(candidateId, 7L)).thenReturn(java.util.Optional.of(candidate));
        when(repository.findByPublicIdAndWorkspaceId(baselineId, 7L)).thenReturn(java.util.Optional.of(baseline));

        EvaluationHistoryService.Comparison comparison = new EvaluationHistoryService(
                workspaceService, repository, objectMapper).compare(workspacePublicId, candidateId, baselineId);

        assertThat(comparison.candidate()).isSameAs(candidate);
        assertThat(comparison.baseline()).isSameAs(baseline);
        assertThat(comparison.gate().passed()).isTrue();
        assertThat(comparison.candidateMetrics()).isEqualTo(candidateMetrics);
        assertThat(comparison.caseDeltas()).singleElement().satisfies(delta -> {
            assertThat(delta.caseId()).isEqualTo("trend-001");
            assertThat(delta.status()).isEqualTo("regressed");
            assertThat(delta.coveredRequiredFactDelta()).isEqualTo(-1);
            assertThat(delta.hitForbiddenClaimDelta()).isEqualTo(1);
        });
    }

    /**
     * 历史列表必须先解析工作区，再按仓储的工作区范围查询，不能返回任何跨工作区批次。
     */
    @Test
    void listsRecentRunsOnlyForValidatedWorkspace() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        EvaluationRunRepository repository = mock(EvaluationRunRepository.class);
        UUID workspacePublicId = UUID.randomUUID();
        com.insightflow.entity.Workspace workspace = mock(com.insightflow.entity.Workspace.class);
        EvaluationRun run = EvaluationRun.create(7L, "gold:v1", "chat:v1", "qwen-test", "none", "{}", "[]");
        when(workspace.getId()).thenReturn(7L);
        when(workspaceService.get(workspacePublicId)).thenReturn(workspace);
        when(repository.findTop100ByWorkspaceIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(run));

        List<EvaluationRun> runs = new EvaluationHistoryService(workspaceService, repository, new ObjectMapper())
                .listRecent(workspacePublicId);

        assertThat(runs).containsExactly(run);
    }
}
