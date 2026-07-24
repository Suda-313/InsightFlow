package com.insightflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.EvaluationRun;
import com.insightflow.evaluation.EvaluationCaseDelta;
import com.insightflow.evaluation.EvaluationCaseRunResult;
import com.insightflow.evaluation.EvaluationCaseScore;
import com.insightflow.entity.Workspace;
import com.insightflow.evaluation.EvaluationRegressionGate;
import com.insightflow.evaluation.GoldEvaluationMetrics;
import com.insightflow.evaluation.GoldEvaluationRunResult;
import com.insightflow.repository.EvaluationRunRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 金标评测历史的写入用例。
 *
 * <p>模型调用由运行器完成，本服务只在模型结果已经形成后校验工作区、序列化脱敏固定题集结果并保存快照；
 * 它不读取真实会话、原始反馈或模型思维链。</p>
 */
@Service
public class EvaluationHistoryService {

    /** 统一解析公开工作区 UUID，禁止调用方直接传入内部主键。 */
    private final WorkspaceService workspaceService;

    /** 评测批次仓储，所有读写都以实体中的 workspace_id 隔离。 */
    private final EvaluationRunRepository evaluationRunRepository;

    /** JSON 序列化器只序列化固定评测结果，避免在实体中耦合 Spring 或模型客户端。 */
    private final ObjectMapper objectMapper;

    /** 质量门禁仅比较固定题集的受控指标，不会根据真实业务数据自动修改任何策略。 */
    private final EvaluationRegressionGate regressionGate = new EvaluationRegressionGate();

    /** 显式注入使工作区边界、存储和序列化能被独立测试。 */
    public EvaluationHistoryService(
            WorkspaceService workspaceService,
            EvaluationRunRepository evaluationRunRepository,
            ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.evaluationRunRepository = evaluationRunRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 将一次完成的金标运行写为不可变历史批次，检索版本当前固定 none，后续 RAG 接入时再传入实际版本。
     */
    @Transactional
    public EvaluationRun record(UUID workspacePublicId, GoldEvaluationRunResult result) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        EvaluationRun run = EvaluationRun.create(
                workspace.getId(),
                result.datasetVersion(),
                result.promptVersion(),
                result.modelName(),
                "none",
                serialize(result.metrics()),
                serialize(result.caseResults()));
        return evaluationRunRepository.save(run);
    }

    /**
     * 返回当前工作区最近 100 次评测批次；逐题输出仍只在详情读取，避免列表响应无限增长。
     */
    @Transactional(readOnly = true)
    public List<EvaluationRun> listRecent(UUID workspacePublicId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        return evaluationRunRepository.findTop100ByWorkspaceIdOrderByCreatedAtDesc(workspace.getId());
    }

    /**
     * 在同一工作区内比较候选与基线批次；数据集版本不同代表题目口径已改变，禁止得出虚假的回归结论。
     */
    @Transactional(readOnly = true)
    public Comparison compare(UUID workspacePublicId, UUID candidateRunId, UUID baselineRunId) {
        Workspace workspace = workspaceService.get(workspacePublicId);
        EvaluationRun candidate = requireRun(workspace.getId(), candidateRunId);
        EvaluationRun baseline = requireRun(workspace.getId(), baselineRunId);
        if (!candidate.getDatasetVersion().equals(baseline.getDatasetVersion())) {
            throw new IllegalArgumentException("不同金标数据集版本不能直接比较");
        }
        GoldEvaluationMetrics baselineMetrics = deserializeMetrics(baseline);
        GoldEvaluationMetrics candidateMetrics = deserializeMetrics(candidate);
        return new Comparison(
                candidate,
                baseline,
                candidateMetrics,
                baselineMetrics,
                compareCases(deserializeCaseResults(baseline), deserializeCaseResults(candidate)),
                regressionGate.compare(baselineMetrics, candidateMetrics));
    }

    /** 公开批次 UUID 必须与已解析的内部工作区键同时命中，防止跨工作区基线引用。 */
    private EvaluationRun requireRun(Long workspaceId, UUID runId) {
        return evaluationRunRepository.findByPublicIdAndWorkspaceId(runId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("评测批次不存在或不属于当前工作区"));
    }

    /** 历史快照只读取汇总指标；逐题结果留给详情接口定位具体回归题目。 */
    private GoldEvaluationMetrics deserializeMetrics(EvaluationRun run) {
        try {
            return objectMapper.readValue(run.getMetricsJson(), GoldEvaluationMetrics.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("评测指标快照解析失败", exception);
        }
    }

    /**
     * 从不可变的 JSON 快照还原单题结果；固定数据集版本相同仍保留缺失项标记，避免历史数据异常被悄悄忽略。
     */
    private List<EvaluationCaseRunResult> deserializeCaseResults(EvaluationRun run) {
        try {
            return objectMapper.readValue(
                    run.getCaseResultsJson(), new TypeReference<List<EvaluationCaseRunResult>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("评测逐题结果快照解析失败", exception);
        }
    }

    /**
     * 以候选题目顺序输出变化，并补上仅存在于基线的异常缺失项；同版本金标集理论上两侧题目集合相同。
     */
    private List<EvaluationCaseDelta> compareCases(
            List<EvaluationCaseRunResult> baselineCases,
            List<EvaluationCaseRunResult> candidateCases) {
        Map<String, EvaluationCaseRunResult> baselineByCaseId = indexByCaseId(baselineCases);
        Map<String, EvaluationCaseRunResult> candidateByCaseId = indexByCaseId(candidateCases);
        List<EvaluationCaseDelta> deltas = new ArrayList<>();
        for (EvaluationCaseRunResult candidate : candidateCases) {
            deltas.add(compareCase(baselineByCaseId.get(candidate.caseId()), candidate));
        }
        for (EvaluationCaseRunResult baseline : baselineCases) {
            if (!candidateByCaseId.containsKey(baseline.caseId())) {
                deltas.add(compareCase(baseline, null));
            }
        }
        return List.copyOf(deltas);
    }

    /** 将固定金标题目按稳定 ID 索引；重复 ID 属于持久化数据损坏，拒绝生成不可靠对比。 */
    private Map<String, EvaluationCaseRunResult> indexByCaseId(List<EvaluationCaseRunResult> caseResults) {
        Map<String, EvaluationCaseRunResult> result = new HashMap<>();
        for (EvaluationCaseRunResult caseResult : caseResults) {
            if (result.put(caseResult.caseId(), caseResult) != null) {
                throw new IllegalStateException("评测逐题结果存在重复 case_id");
            }
        }
        return result;
    }

    /**
     * 单题的变化只使用可复现的计数和布尔规则：一侧缺失标记为 missing；同时存在正负变化时保留 mixed，
     * 避免用单一分数掩盖“事实覆盖提升但编造增加”的风险。
     */
    private EvaluationCaseDelta compareCase(
            EvaluationCaseRunResult baseline,
            EvaluationCaseRunResult candidate) {
        EvaluationCaseRunResult present = candidate == null ? baseline : candidate;
        if (baseline == null || candidate == null || baseline.score() == null || candidate.score() == null) {
            return new EvaluationCaseDelta(
                    present.caseId(), present.category(), "missing", null, null, null, null);
        }
        EvaluationCaseScore baselineScore = baseline.score();
        EvaluationCaseScore candidateScore = candidate.score();
        int coveredDelta = candidateScore.coveredRequiredFactCount() - baselineScore.coveredRequiredFactCount();
        int forbiddenDelta = candidateScore.hitForbiddenClaimCount() - baselineScore.hitForbiddenClaimCount();
        boolean refusalChanged = candidateScore.refusalCompliant() != baselineScore.refusalCompliant();
        boolean specificChanged = candidateScore.answerSpecific() != baselineScore.answerSpecific();
        int improvements = (coveredDelta > 0 ? 1 : 0)
                + (forbiddenDelta < 0 ? 1 : 0)
                + (!baselineScore.refusalCompliant() && candidateScore.refusalCompliant() ? 1 : 0)
                + (!baselineScore.answerSpecific() && candidateScore.answerSpecific() ? 1 : 0)
                + ("failed".equals(baseline.status()) && "succeeded".equals(candidate.status()) ? 1 : 0);
        int regressions = (coveredDelta < 0 ? 1 : 0)
                + (forbiddenDelta > 0 ? 1 : 0)
                + (baselineScore.refusalCompliant() && !candidateScore.refusalCompliant() ? 1 : 0)
                + (baselineScore.answerSpecific() && !candidateScore.answerSpecific() ? 1 : 0)
                + ("succeeded".equals(baseline.status()) && "failed".equals(candidate.status()) ? 1 : 0);
        return new EvaluationCaseDelta(
                candidate.caseId(),
                candidate.category(),
                changeStatus(improvements, regressions),
                coveredDelta,
                forbiddenDelta,
                refusalChanged ? candidateScore.refusalCompliant() : null,
                specificChanged ? candidateScore.answerSpecific() : null);
    }

    /** 将各维度正负变化收敛为可展示状态，但不把 mixed 当作通过或失败结论。 */
    private String changeStatus(int improvements, int regressions) {
        if (improvements > 0 && regressions > 0) {
            return "mixed";
        }
        if (improvements > 0) {
            return "improved";
        }
        if (regressions > 0) {
            return "regressed";
        }
        return "unchanged";
    }

    /** 序列化失败代表服务端契约错误，不能生成缺失指标或逐题结果的不完整基线。 */
    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("评测结果序列化失败", exception);
        }
    }

    /** 两个已隔离历史批次与质量门禁结论的受控组合，不向 HTTP 暴露内部数据库主键。 */
    public record Comparison(
            EvaluationRun candidate,
            EvaluationRun baseline,
            GoldEvaluationMetrics candidateMetrics,
            GoldEvaluationMetrics baselineMetrics,
            List<EvaluationCaseDelta> caseDeltas,
            EvaluationRegressionGate.Comparison gate) {
    }
}
