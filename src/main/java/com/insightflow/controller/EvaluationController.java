package com.insightflow.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.config.AgentApiKeyPresentCondition;
import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.EvaluationRun;
import com.insightflow.evaluation.GoldEvaluationRunResult;
import com.insightflow.evaluation.GoldEvaluationRunner;
import com.insightflow.evaluation.rag.RagEvaluationMetrics;
import com.insightflow.evaluation.rag.RagEvaluationTaskCommandService;
import com.insightflow.evaluation.rag.RagEvaluationTaskQueryService;
import com.insightflow.service.EvaluationHistoryService;
import com.insightflow.service.RagEvaluationHistoryService;
import com.insightflow.entity.RagEvaluationRun;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固定金标集的评测 HTTP 边界。
 *
 * <p>评测输入是仓库内的脱敏 fixture，不会读取当前工作区的舆情、会话或报告内容；但接口仍放在工作区路径下，
 * 先校验访问的工作区存在，为后续按工作区保存版本基线和对比结果保留一致的隔离边界。</p>
 */
@RestController
@Conditional(AgentApiKeyPresentCondition.class)
@RequestMapping("/api/v1/workspaces/{workspaceId}/evaluations")
public class EvaluationController {

    /** 评测运行器封装模型调用、规则打分和结果汇总，Controller 不拼接 Prompt。 */
    private final GoldEvaluationRunner evaluationRunner;

    /** 历史服务负责工作区校验和不可变结果快照写入。 */
    private final EvaluationHistoryService evaluationHistoryService;

    /** RAG 运行器复用线上受控检索和 Prompt 护栏，不能由 Controller 自行拼接模型调用。 */
    /** RAG 专项历史独立于通用金标历史，避免不同 JSON 指标口径混存。 */
    private final RagEvaluationHistoryService ragEvaluationHistoryService;

    /** RAG 评测命令负责完成授权检查和持久化受理，Controller 不能在 HTTP 线程调用模型。*/
    private final RagEvaluationTaskCommandService ragEvaluationTaskCommandService;
    /** 轮询查询必须经由授权服务，任务 UUID 不能单独作为读取凭证。*/
    private final RagEvaluationTaskQueryService ragEvaluationTaskQueryService;

    /** 反序列化历史批次中已持久化的脱敏指标 JSON，供列表接口返回三项 RAG 质量指标。 */
    private final ObjectMapper objectMapper;

    /** 通过构造器明确 HTTP 校验与评测执行的职责边界，便于单测替换模型运行器。 */
    public EvaluationController(
            GoldEvaluationRunner evaluationRunner,
            EvaluationHistoryService evaluationHistoryService,
            RagEvaluationHistoryService ragEvaluationHistoryService,
            RagEvaluationTaskCommandService ragEvaluationTaskCommandService,
            RagEvaluationTaskQueryService ragEvaluationTaskQueryService,
            ObjectMapper objectMapper) {
        this.evaluationRunner = evaluationRunner;
        this.evaluationHistoryService = evaluationHistoryService;
        this.ragEvaluationHistoryService = ragEvaluationHistoryService;
        this.ragEvaluationTaskCommandService = ragEvaluationTaskCommandService;
        this.ragEvaluationTaskQueryService = ragEvaluationTaskQueryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 运行一次完整金标评测并同步返回结果。
     *
     * <p>该操作会产生模型调用成本，当前仅供开发环境人工触发；运行器对单题失败做受控收敛，
     * 因此调用方始终能拿到整批的成功、失败与成本统计，而不会把供应商异常原文暴露给客户端。</p>
     */
    @PostMapping("/gold")
    public GoldRunResponse runGoldEvaluation(@PathVariable UUID workspaceId) {
        GoldEvaluationRunResult result = evaluationRunner.run();
        EvaluationRun persisted = evaluationHistoryService.record(workspaceId, result);
        return new GoldRunResponse(persisted.getPublicId(), result);
    }

    /**
     * 列出当前工作区可作为基线的最近评测批次；列表不加载指标 JSON 和逐题输出，详情按需读取。
     */
    @GetMapping("/gold")
    public List<RunSummaryResponse> listGoldEvaluationRuns(@PathVariable UUID workspaceId) {
        return evaluationHistoryService.listRecent(workspaceId).stream()
                .map(RunSummaryResponse::from)
                .toList();
    }

    /**
     * 运行一次当前 Workspace 的 RAG 专项评测。
     *
     * <p>题集只来自该 Workspace 可见的已发布知识，运行器最多按题集执行固定次数的检索与模型调用；
     * 返回和持久化内容仅包含脱敏指标、证据计数与批次 UUID。</p>
     */
    @PostMapping("/rag")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RagTaskResponse runRagEvaluation(@PathVariable UUID workspaceId) {
        AsyncTask task = ragEvaluationTaskCommandService.enqueue(workspaceId);
        return RagTaskResponse.from(task);
    }

    /** 读取任务终态供前端轮询；只公开状态、对应运行 UUID 和受控错误码。*/
    @GetMapping("/rag/tasks/{taskId}")
    public RagTaskStatusResponse getRagEvaluationTask(@PathVariable UUID workspaceId, @PathVariable UUID taskId) {
        return RagTaskStatusResponse.from(ragEvaluationTaskQueryService.get(workspaceId, taskId));
    }

    /** 返回当前 Workspace 的 RAG 历史摘要及脱敏指标，供页面展示召回、引用与无依据回答率。 */
    @GetMapping("/rag")
    public List<RagRunSummaryResponse> listRagEvaluationRuns(@PathVariable UUID workspaceId) {
        return ragEvaluationHistoryService.listRecent(workspaceId).stream()
                .map(run -> RagRunSummaryResponse.from(run, objectMapper))
                .toList();
    }

    /**
     * 比较同一工作区内的候选与基线批次；路径中的两个标识都是公开 UUID，
     * 历史服务负责验证归属、数据集一致性和质量门禁，Controller 不自行读取仓储。
     */
    @GetMapping("/gold/{candidateRunId}/compare/{baselineRunId}")
    public ComparisonResponse compareGoldEvaluation(
            @PathVariable UUID workspaceId,
            @PathVariable UUID candidateRunId,
            @PathVariable UUID baselineRunId) {
        EvaluationHistoryService.Comparison comparison = evaluationHistoryService.compare(
                workspaceId, candidateRunId, baselineRunId);
        return new ComparisonResponse(
                comparison.candidate().getPublicId(),
                comparison.baseline().getPublicId(),
                comparison.candidate().getDatasetVersion(),
                comparison.candidate().getPromptVersion(),
                comparison.baseline().getPromptVersion(),
                comparison.candidateMetrics(),
                comparison.baselineMetrics(),
                comparison.caseDeltas(),
                comparison.gate().passed(),
                comparison.gate().violations());
    }

    /**
     * 运行响应只公开评测批次 UUID 和受控评测结果，实体内部主键、workspace_id 与 JSON 存储细节均不暴露。
     */
    public record GoldRunResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("run_id") UUID runId,
            GoldEvaluationRunResult result) {
    }

    /** RAG 运行响应不暴露内部数据库键、文档正文或模型原始回答。 */
    public record RagTaskResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("task_id") UUID taskId,
            String status) {

        /** 从任务实体显式投影，避免 API 暴露内部主键、租约或幂等键。*/
        static RagTaskResponse from(AsyncTask task) {
            return new RagTaskResponse(task.getPublicId(), task.getStatus());
        }
    }

    /** 终态中的 run_id 指向既有 RAG 历史，不在轮询响应中重复暴露指标 JSON。*/
    public record RagTaskStatusResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("task_id") UUID taskId,
            String status,
            @com.fasterxml.jackson.annotation.JsonProperty("run_id") String runId,
            @com.fasterxml.jackson.annotation.JsonProperty("error_code") String errorCode) {
        static RagTaskStatusResponse from(AsyncTask task) {
            // 只有成功任务的 result_json 才约定包含历史批次 UUID；partial_failed 保存的是失败摘要。
            String runId = "succeeded".equals(task.getStatus()) && task.getResultJson() != null
                    ? task.getResultJson().replaceAll(".*\\\"run\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1")
                    : null;
            return new RagTaskStatusResponse(task.getPublicId(), task.getStatus(), runId, task.getErrorCode());
        }
    }

    /** RAG 历史摘要提供版本元数据与三项脱敏指标，不包含逐题模型回答或知识原文。 */
    public record RagRunSummaryResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("run_id") UUID runId,
            @com.fasterxml.jackson.annotation.JsonProperty("dataset_version") String datasetVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("prompt_version") String promptVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("model_name") String modelName,
            @com.fasterxml.jackson.annotation.JsonProperty("retrieval_version") String retrievalVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("created_at") OffsetDateTime createdAt,
            RagEvaluationMetrics metrics) {

        /** 实体到公开 DTO 的投影显式排除内部 Workspace 键与逐题 JSON 快照。 */
        static RagRunSummaryResponse from(RagEvaluationRun run, ObjectMapper objectMapper) {
            return new RagRunSummaryResponse(
                    run.getPublicId(), run.getDatasetVersion(), run.getPromptVersion(),
                    run.getModelName(), run.getRetrievalVersion(), run.getCreatedAt(),
                    readMetrics(run.getMetricsJson(), objectMapper));
        }

        /** 指标 JSON 损坏时直接失败，避免页面展示空指标却误以为评测成功。 */
        private static RagEvaluationMetrics readMetrics(String metricsJson, ObjectMapper objectMapper) {
            try {
                return objectMapper.readValue(metricsJson, RagEvaluationMetrics.class);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("无法读取 RAG 评测指标快照", exception);
            }
        }
    }

    /** 评测历史摘要只公开版本、模型和创建时间，供用户选择基线和追踪实验批次。 */
    public record RunSummaryResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("run_id") UUID runId,
            @com.fasterxml.jackson.annotation.JsonProperty("dataset_version") String datasetVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("prompt_version") String promptVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("model_name") String modelName,
            @com.fasterxml.jackson.annotation.JsonProperty("retrieval_version") String retrievalVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("created_at") OffsetDateTime createdAt) {

        /** 实体到 API 的映射刻意不携带内部键和 JSON 快照。 */
        static RunSummaryResponse from(EvaluationRun run) {
            return new RunSummaryResponse(
                    run.getPublicId(),
                    run.getDatasetVersion(),
                    run.getPromptVersion(),
                    run.getModelName(),
                    run.getRetrievalVersion(),
                    run.getCreatedAt());
        }
    }

    /**
     * 比较响应同时携带两侧汇总指标与逐题规则变化，不返回模型输出正文或内部主键；
     * 前端可直接定位 Prompt 变更影响的题目、延迟和 Token，质量门禁仍以 violations 为准。
     */
    public record ComparisonResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("candidate_run_id") UUID candidateRunId,
            @com.fasterxml.jackson.annotation.JsonProperty("baseline_run_id") UUID baselineRunId,
            @com.fasterxml.jackson.annotation.JsonProperty("dataset_version") String datasetVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("candidate_prompt_version") String candidatePromptVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("baseline_prompt_version") String baselinePromptVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("candidate_metrics") com.insightflow.evaluation.GoldEvaluationMetrics candidateMetrics,
            @com.fasterxml.jackson.annotation.JsonProperty("baseline_metrics") com.insightflow.evaluation.GoldEvaluationMetrics baselineMetrics,
            @com.fasterxml.jackson.annotation.JsonProperty("case_deltas") java.util.List<com.insightflow.evaluation.EvaluationCaseDelta> caseDeltas,
            boolean passed,
            java.util.List<String> violations) {
    }
}
