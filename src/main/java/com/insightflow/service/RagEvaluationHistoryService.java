package com.insightflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.RagEvaluationRun;
import com.insightflow.entity.Workspace;
import com.insightflow.evaluation.rag.RagEvaluationRunResult;
import com.insightflow.repository.RagEvaluationRunRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG 评测历史的 Workspace 隔离写入与读取用例。
 *
 * <p>它只接收已经运行完成的脱敏结果，不调用模型、不检索文档，也不接触 MinIO；
 * 因此评测执行成本和历史存储职责保持分离。</p>
 */
@Service
public class RagEvaluationHistoryService {

    /** Workspace 服务将外部 UUID 解析为可信内部隔离键。 */
    private final WorkspaceService workspaceService;

    /** 专项仓储与通用 Prompt 金标仓储分离，防止 JSON 口径混用。 */
    private final RagEvaluationRunRepository repository;

    /** 仅序列化固定指标和逐题计数，序列化失败时拒绝生成不完整历史。 */
    private final ObjectMapper objectMapper;

    /** 显式注入边界依赖，支持用单测验证无跨 Workspace 写入。 */
    public RagEvaluationHistoryService(
            WorkspaceService workspaceService, RagEvaluationRunRepository repository, ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 在同一事务中解析 Workspace、固化 JSON 并写入不可变 RAG 历史批次。 */
    @Transactional
    public RagEvaluationRun record(UUID workspacePublicId, RagEvaluationRunResult result) {
        return recordInternal(workspacePublicId, result, result.caseResults());
    }

    /**
     * 人工金标 Runner 专用：逐题结果使用脱敏 {@code RagGoldManualEvaluationCaseResult} JSON。
     */
    @Transactional
    public RagEvaluationRun recordManual(
            UUID workspacePublicId,
            RagEvaluationRunResult result,
            List<?> manualCaseResults) {
        return recordInternal(workspacePublicId, result, manualCaseResults);
    }

    private RagEvaluationRun recordInternal(UUID workspacePublicId, RagEvaluationRunResult result, Object caseResults) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        RagEvaluationRun run = RagEvaluationRun.create(
                workspace.getId(), result.datasetVersion(), result.promptVersion(), result.modelName(),
                result.retrievalVersion(), serialize(result.metrics()), serialize(caseResults));
        return repository.save(run);
    }

    /** 返回当前 Workspace 的最新批次，不加载 JSON 正文以控制列表响应和泄露面。 */
    @Transactional(readOnly = true)
    public List<RagEvaluationRun> listRecent(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        return repository.findTop100ByWorkspaceIdOrderByCreatedAtDesc(workspace.getId());
    }

    /** JSON 失败代表服务端契约错误，不能悄悄存储空指标后伪装为有效基线。 */
    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 RAG 评测脱敏快照", exception);
        }
    }
}
