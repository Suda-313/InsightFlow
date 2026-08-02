package com.insightflow.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.insightflow.entity.AgentRun;
import com.insightflow.service.AgentRunService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AgentRun 只读 HTTP 边界，供排障、评测样本抽取和后续成本看板读取。
 *
 * <p>接口只暴露 Trace UUID，不返回数据库内部 id；每次请求都把 workspaceId 传递给服务层完成归属校验。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/agent-runs")
public class AgentRunController {

    /** 生命周期服务统一处理工作区隔离和最近记录限制。 */
    private final AgentRunService agentRunService;

    /** 通过构造器注入，保持 Controller 只负责 HTTP 契约转换。 */
    public AgentRunController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    /** 返回当前工作区最近 100 条 AgentRun 的轻量摘要，不加载输入和输出正文。 */
    @GetMapping
    public List<RunSummaryResponse> list(@PathVariable UUID workspaceId) {
        return agentRunService.listRecent(workspaceId).stream()
                .map(RunSummaryResponse::from)
                .toList();
    }

    /**
     * 返回最近审计样本的 Agent、Prompt 和模型分组百分位，供性能优化前后建立同口径基线；
     * 聚合过程由服务层按 workspaceId 完成，不允许 Controller 直接读取跨工作区运行记录。
     */
    @GetMapping("/metrics")
    public AgentRunService.PerformanceBaseline performanceBaseline(@PathVariable UUID workspaceId) {
        return agentRunService.performanceBaseline(workspaceId);
    }

    /** 返回一条已验证工作区归属的完整运行详情，用于定位具体模型调用。 */
    @GetMapping("/{traceId}")
    public RunDetailResponse get(@PathVariable UUID workspaceId, @PathVariable UUID traceId) {
        return RunDetailResponse.from(agentRunService.get(workspaceId, traceId));
    }

    /**
     * 运行列表的公开摘要；Trace 是对外标识，状态、版本、耗时和错误码用于筛选与比较。
     */
    public record RunSummaryResponse(
            @JsonProperty("trace_id") UUID traceId,
            @JsonProperty("agent_type") String agentType,
            String status,
            @JsonProperty("model_name") String modelName,
            @JsonProperty("latency_ms") Long latencyMs,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("completed_at") OffsetDateTime completedAt) {

        /** 从实体转换时刻意不返回内部 id、输入摘要、输出或证据正文。 */
        static RunSummaryResponse from(AgentRun run) {
            return new RunSummaryResponse(
                    run.getPublicId(),
                    run.getAgentType(),
                    run.getStatus(),
                    run.getModelName(),
                    run.getLatencyMs(),
                    run.getErrorCode(),
                    run.getCreatedAt(),
                    run.getCompletedAt());
        }
    }

    /**
     * 单条运行详情；只暴露已脱敏输入摘要、最终回答和受控证据 JSON，不包含思维链或内部异常。
     */
    public record RunDetailResponse(
            @JsonProperty("trace_id") UUID traceId,
            @JsonProperty("agent_type") String agentType,
            String status,
            @JsonProperty("prompt_version") String promptVersion,
            @JsonProperty("model_name") String modelName,
            @JsonProperty("retrieval_version") String retrievalVersion,
            @JsonProperty("input_summary") String inputSummary,
            @JsonProperty("output_text") String outputText,
            @JsonRawValue @JsonProperty("evidence") String evidenceJson,
            @JsonProperty("prompt_tokens") Long promptTokens,
            @JsonProperty("completion_tokens") Long completionTokens,
            @JsonProperty("total_tokens") Long totalTokens,
            @JsonProperty("latency_ms") Long latencyMs,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("completed_at") OffsetDateTime completedAt) {

        /** 实体字段与 API 字段显式映射，保持 snake_case 契约且不暴露数据库内部键。 */
        static RunDetailResponse from(AgentRun run) {
            return new RunDetailResponse(
                    run.getPublicId(),
                    run.getAgentType(),
                    run.getStatus(),
                    run.getPromptVersion(),
                    run.getModelName(),
                    run.getRetrievalVersion(),
                    run.getInputSummary(),
                    run.getOutputText(),
                    run.getEvidenceJson(),
                    run.getPromptTokens(),
                    run.getCompletionTokens(),
                    run.getTotalTokens(),
                    run.getLatencyMs(),
                    run.getErrorCode(),
                    run.getCreatedAt(),
                    run.getCompletedAt());
        }
    }
}
