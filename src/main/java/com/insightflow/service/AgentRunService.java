package com.insightflow.service;

import com.insightflow.common.exception.AgentRunNotFoundException;
import com.insightflow.entity.AgentRun;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.AgentRunRepository;
import com.insightflow.service.importing.PiiSanitizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AgentRun 生命周期用例，集中落实工作区隔离、脱敏摘要和受控状态转换。
 *
 * <p>该服务不调用模型，也不决定业务结论；各 Agent 只在调用前后报告开始、成功或失败，避免审计逻辑散落在
 * Controller、模型适配器和业务服务中。</p>
 */
@Service
@Transactional(readOnly = true)
public class AgentRunService {

    /** 解析公开工作区 UUID，确保审计写入不会相信客户端提供的内部 id。 */
    private final WorkspaceService workspaceService;

    /** 运行仓储提供工作区范围内的生命周期读写。 */
    private final AgentRunRepository agentRunRepository;

    /** 输入摘要复用导入链路的 PII 脱敏规则，避免审计记录扩大敏感信息范围。 */
    private final PiiSanitizer piiSanitizer;

    /** 通过构造器注入便于不同 Agent 复用同一审计用例并进行单元测试。 */
    public AgentRunService(
            WorkspaceService workspaceService,
            AgentRunRepository agentRunRepository,
            PiiSanitizer piiSanitizer) {
        this.workspaceService = workspaceService;
        this.agentRunRepository = agentRunRepository;
        this.piiSanitizer = piiSanitizer;
    }

    /**
     * 在模型请求开始前创建 running 记录，返回的 publicId 同时是日志与 API 可关联的 Trace。
     */
    @Transactional
    public AgentRun start(UUID workspacePublicId, StartRequest request) {
        Long workspaceId = resolveWorkspaceId(workspacePublicId);
        String summary = summarizeInput(request.inputSummary());
        AgentRun run = AgentRun.start(
                workspaceId,
                request.agentType(),
                request.promptVersion(),
                request.modelName(),
                request.retrievalVersion(),
                summary);
        return agentRunRepository.save(run);
    }

    /** 将运行转为成功终态并记录最终用户可见答案、Usage 与耗时。 */
    @Transactional
    public void succeed(UUID workspacePublicId, UUID traceId, Completion completion) {
        AgentRun run = requireRun(workspacePublicId, traceId);
        run.succeed(
                completion.outputText(),
                completion.evidenceJson(),
                completion.promptTokens(),
                completion.completionTokens(),
                completion.totalTokens(),
                completion.latencyMs());
        agentRunRepository.save(run);
    }

    /** 将运行转为失败终态；错误码固定为模型调用失败，详细异常只由调用方记录到服务端日志。 */
    @Transactional
    public void fail(UUID workspacePublicId, UUID traceId, long latencyMs) {
        AgentRun run = requireRun(workspacePublicId, traceId);
        run.fail("MODEL_CALL_FAILED", latencyMs);
        agentRunRepository.save(run);
    }

    /** 返回工作区最近 100 条运行记录，满足首版排查与评测样本抽取。 */
    public List<AgentRun> listRecent(UUID workspacePublicId) {
        Long workspaceId = resolveWorkspaceId(workspacePublicId);
        return agentRunRepository.findTop100ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /** 读取单条运行详情，跨工作区 Trace 与不存在 Trace 均返回领域 404。 */
    public AgentRun get(UUID workspacePublicId, UUID traceId) {
        return requireRun(workspacePublicId, traceId);
    }

    /**
     * 基于当前工作区最近 100 条审计记录构建性能基线；只纳入已成功且有服务端耗时的调用，
     * 并按 Agent、Prompt 与模型分组，防止实现或版本变化被平均数掩盖。
     */
    public PerformanceBaseline performanceBaseline(UUID workspacePublicId) {
        Map<PerformanceKey, List<AgentRun>> groupedRuns = new LinkedHashMap<>();
        for (AgentRun run : listRecent(workspacePublicId)) {
            if (!"succeeded".equals(run.getStatus()) || run.getLatencyMs() == null) {
                continue;
            }
            PerformanceKey key = new PerformanceKey(run.getAgentType(), run.getPromptVersion(), run.getModelName());
            groupedRuns.computeIfAbsent(key, ignored -> new ArrayList<>()).add(run);
        }
        List<PerformanceMetric> metrics = groupedRuns.entrySet().stream()
                .map(entry -> summarizePerformance(entry.getKey(), entry.getValue()))
                .toList();
        return new PerformanceBaseline(100, metrics);
    }

    /** 计算一个稳定分组的 p50/p95；Token 任一采样缺失时不猜测百分位，返回 null 提示 Usage 尚不可用。 */
    private PerformanceMetric summarizePerformance(PerformanceKey key, List<AgentRun> runs) {
        return new PerformanceMetric(
                key.agentType(),
                key.promptVersion(),
                key.modelName(),
                runs.size(),
                percentile(runs, AgentRun::getLatencyMs, false, 0.50),
                percentile(runs, AgentRun::getLatencyMs, false, 0.95),
                percentile(runs, AgentRun::getPromptTokens, true, 0.50),
                percentile(runs, AgentRun::getPromptTokens, true, 0.95),
                percentile(runs, AgentRun::getCompletionTokens, true, 0.50),
                percentile(runs, AgentRun::getCompletionTokens, true, 0.95));
    }

    /**
     * 使用 nearest-rank 口径，样本量较小时 p95 自然落在最慢样本；该选择与金标评测运行器保持一致。
     */
    private Long percentile(
            List<AgentRun> runs,
            Function<AgentRun, Long> valueExtractor,
            boolean returnNullWhenMissing,
            double percentile) {
        List<Long> values = runs.stream().map(valueExtractor).toList();
        if (returnNullWhenMissing && values.stream().anyMatch(java.util.Objects::isNull)) {
            return null;
        }
        List<Long> sorted = values.stream().filter(java.util.Objects::nonNull).sorted(Comparator.naturalOrder()).toList();
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(index);
    }

    /** 先解析工作区再按复合条件读取运行记录，保持所有公开读取路径的隔离一致。 */
    private AgentRun requireRun(UUID workspacePublicId, UUID traceId) {
        Long workspaceId = resolveWorkspaceId(workspacePublicId);
        return agentRunRepository.findByPublicIdAndWorkspaceId(traceId, workspaceId)
                .orElseThrow(() -> new AgentRunNotFoundException(traceId));
    }

    /** 将输入摘要先脱敏、再截断至 500 字符，既支持排查又不成为完整对话备份。 */
    private String summarizeInput(String input) {
        String sanitized = piiSanitizer.sanitize(input == null ? "" : input);
        return sanitized.length() > 500 ? sanitized.substring(0, 500) + "…" : sanitized;
    }

    /** 统一将外部 UUID 转为可信内部工作区主键。 */
    private Long resolveWorkspaceId(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        return workspace.getId();
    }

    /** 创建运行时必填的版本与输入元数据；完整 Prompt 不属于记录内容。 */
    public record StartRequest(
            String agentType,
            String promptVersion,
            String modelName,
            String retrievalVersion,
            String inputSummary) {
    }

    /** 模型成功后补齐的最终结果和 Usage；Usage 缺失时 Token 字段可为 null。 */
    public record Completion(
            String outputText,
            String evidenceJson,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            long latencyMs) {
    }

    /** 分组键刻意只包含会改变模型调用特征的 Agent、Prompt 与模型，不按瞬态 Trace 或用户数据拆分。 */
    private record PerformanceKey(String agentType, String promptVersion, String modelName) {
    }

    /** 最近 100 条运行记录计算出的工作区性能快照，不代表全量历史或供应商端到端网络耗时。 */
    public record PerformanceBaseline(int sampleLimit, List<PerformanceMetric> metrics) {
    }

    /**
     * 单一 Agent/Prompt/模型组合的服务端耗时与 Usage 百分位；Token 百分位为 null 表示至少一条样本没有供应商 Usage。
     */
    public record PerformanceMetric(
            String agentType,
            String promptVersion,
            String modelName,
            int succeededSampleCount,
            Long p50LatencyMs,
            Long p95LatencyMs,
            Long p50PromptTokens,
            Long p95PromptTokens,
            Long p50CompletionTokens,
            Long p95CompletionTokens) {
    }
}
