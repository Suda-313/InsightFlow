package com.insightflow.controller;

import com.insightflow.entity.ActionExecution;
import com.insightflow.entity.ActionProposal;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.entity.InvestigationEvidenceSnapshot;
import com.insightflow.entity.Workspace;
import com.insightflow.entity.CorrectionKind;
import com.insightflow.entity.ManualCorrection;
import com.insightflow.correction.CorrectionCommandService;
import com.insightflow.correction.CorrectionPublicationService;
import com.insightflow.investigation.InvestigationCommandService;
import com.insightflow.investigation.FollowUpCommandService;
import com.insightflow.proposal.ProposalCommandService;
import com.insightflow.proposal.ProposalPreviewService;
import com.insightflow.risk.RiskQueueService;
import com.insightflow.repository.ActionProposalRepository;
import com.insightflow.repository.ActionExecutionRepository;
import com.insightflow.repository.InvestigationCaseRepository;
import com.insightflow.repository.InvestigationEvidenceSnapshotRepository;
import com.insightflow.security.WorkspaceAccessService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 调查中心的单一 HTTP 入口：读取卡片、查看证据、预览提案、人工执行和撤销。
 *
 * <p>Controller 只做公开 UUID 契约投影，不直接修改实体。命令都由权限服务与 ProposalCommandService 收敛，Agent 不具备这些 HTTP 写入路径。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/investigations")
public class InvestigationController {

    /** 调查重试命令会创建或复用幂等调查卡片。 */
    private final InvestigationCommandService investigationCommandService;
    /** 开始跟进命令只记录响应事实，不承担提案执行或调查取证职责。 */
    private final FollowUpCommandService followUpCommandService;
    /** 风险队列将冻结快照投影为运营待办，不在 Controller 重算任何分数。 */
    private final RiskQueueService riskQueueService;
    /** 读取前统一完成 Workspace 范围校验。 */
    private final WorkspaceAccessService accessService;
    /** 卡片仓储只经 Workspace 内部键读取。 */
    private final InvestigationCaseRepository investigationCaseRepository;
    /** 快照仓储提供冻结证据。 */
    private final InvestigationEvidenceSnapshotRepository evidenceRepository;
    /** 提案仓储用于卡片详情展示，不执行任何状态变更。 */
    private final ActionProposalRepository proposalRepository;
    /** 执行记录与提案分离保存，详情需要同时返回两者以支撑刷新后的撤销入口。 */
    private final ActionExecutionRepository executionRepository;
    /** 预览服务不写状态。 */
    private final ProposalPreviewService previewService;
    /** 执行和撤销命令包含角色、幂等和审计。 */
    private final ProposalCommandService commandService;
    /** 纠错提交只创建候选，不会直接覆盖规则或历史数据。 */
    private final CorrectionCommandService correctionCommandService;
    /** 纠错发布必须执行双评测门禁与审计。 */
    private final CorrectionPublicationService correctionPublicationService;

    /** 构造器明确区分只读查询、提案预览和人工命令。 */
    public InvestigationController(
            InvestigationCommandService investigationCommandService,
            FollowUpCommandService followUpCommandService,
            RiskQueueService riskQueueService,
            WorkspaceAccessService accessService,
            InvestigationCaseRepository investigationCaseRepository,
            InvestigationEvidenceSnapshotRepository evidenceRepository,
            ActionProposalRepository proposalRepository,
            ActionExecutionRepository executionRepository,
            ProposalPreviewService previewService,
            ProposalCommandService commandService,
            CorrectionCommandService correctionCommandService,
            CorrectionPublicationService correctionPublicationService) {
        this.investigationCommandService = investigationCommandService;
        this.followUpCommandService = followUpCommandService;
        this.riskQueueService = riskQueueService;
        this.accessService = accessService;
        this.investigationCaseRepository = investigationCaseRepository;
        this.evidenceRepository = evidenceRepository;
        this.proposalRepository = proposalRepository;
        this.executionRepository = executionRepository;
        this.previewService = previewService;
        this.commandService = commandService;
        this.correctionCommandService = correctionCommandService;
        this.correctionPublicationService = correctionPublicationService;
    }

    /** 调查中心列表只返回当前 Workspace 已授权范围内的卡片。 */
    @GetMapping
    public List<CaseResponse> list(@PathVariable UUID workspaceId) {
        Workspace workspace = accessService.requireRead(workspaceId);
        return investigationCaseRepository.findByWorkspaceIdOrderByUpdatedAtDesc(workspace.getId()).stream()
                .map(CaseResponse::from).toList();
    }

    /** 首页和调查中心共用的风险待办入口，结果按冻结优先级降序返回。 */
    @GetMapping("/risk-queue")
    public List<RiskQueueService.RiskQueueItem> riskQueue(@PathVariable UUID workspaceId) {
        return riskQueueService.list(workspaceId);
    }

    /** 查询一张卡片时同时返回冻结证据和待审/已执行提案。 */
    @GetMapping("/{caseId}")
    public CaseDetailResponse detail(@PathVariable UUID workspaceId, @PathVariable UUID caseId) {
        Workspace workspace = accessService.requireRead(workspaceId);
        InvestigationCase investigation = findCase(workspace, caseId);
        return new CaseDetailResponse(
                CaseResponse.from(investigation),
                evidenceRepository.findByInvestigationCaseIdAndWorkspaceIdOrderByCreatedAtAsc(investigation.getId(), workspace.getId())
                        .stream().map(EvidenceResponse::from).toList(),
                proposalRepository.findByWorkspaceIdAndInvestigationCaseIdOrderByCreatedAtAsc(workspace.getId(), investigation.getId())
                        .stream().map(ProposalResponse::from).toList(),
                executionRepository.findByWorkspaceIdAndInvestigationCaseIdOrderByCreatedAtAsc(workspace.getId(), investigation.getId())
                        .stream().map(ExecutionResponse::from).toList());
    }

    /** 用户可对当前工作区告警请求调查，重复请求返回既有卡片而不会重复排队。 */
    @PostMapping("/alerts/{alertId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CaseResponse enqueue(@PathVariable UUID workspaceId, @PathVariable UUID alertId) {
        return CaseResponse.from(investigationCommandService.enqueue(workspaceId, alertId));
    }

    /**
     * 由具备分析或运营权限的成员显式开始跟进；不接收前端传入的操作人，
     * 也不把卡片锁定给单一成员，保持首期非派单式响应闭环。
     */
    @PostMapping("/{caseId}/follow-up")
    public CaseResponse startFollowUp(@PathVariable UUID workspaceId, @PathVariable UUID caseId) {
        return CaseResponse.from(followUpCommandService.start(workspaceId, caseId));
    }

    /** 人工执行前取得服务端生成的影响预览。 */
    @PostMapping("/{caseId}/proposals/{proposalId}/preview")
    public ProposalPreviewService.ProposalPreview preview(
            @PathVariable UUID workspaceId, @PathVariable UUID caseId, @PathVariable UUID proposalId) {
        return previewService.preview(workspaceId, caseId, proposalId);
    }

    /** 只有具备角色权限的人工请求能执行，Idempotency-Key 是必填命令语义。 */
    @PostMapping("/{caseId}/proposals/{proposalId}/execute")
    public ExecutionResponse execute(
            @PathVariable UUID workspaceId,
            @PathVariable UUID caseId,
            @PathVariable UUID proposalId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ExecutionResponse.from(commandService.execute(workspaceId, caseId, proposalId, idempotencyKey));
    }

    /** 撤销只作用于同一调查的已执行记录，并恢复到待复核而不是删除事实。 */
    @PostMapping("/{caseId}/executions/{executionId}/undo")
    public ExecutionResponse undo(
            @PathVariable UUID workspaceId, @PathVariable UUID caseId, @PathVariable UUID executionId) {
        return ExecutionResponse.from(commandService.undo(workspaceId, caseId, executionId));
    }

    /** 提交纠错候选，仅 Owner 或 Analyst 可用，结果保持待复核而非直接生效。 */
    @PostMapping("/{caseId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionResponse submitCorrection(
            @PathVariable UUID workspaceId, @PathVariable UUID caseId, @Valid @RequestBody CorrectionRequest request) {
        return CorrectionResponse.from(correctionCommandService.submit(workspaceId, caseId, request.kind(), request.content()));
    }

    /** Owner 在指定金标与 RAG 基线、候选均通过后才可以发布纠错候选。 */
    @PostMapping("/{caseId}/corrections/{correctionId}/approve")
    public CorrectionResponse approveCorrection(
            @PathVariable UUID workspaceId,
            @PathVariable UUID caseId,
            @PathVariable UUID correctionId) {
        return CorrectionResponse.from(correctionPublicationService.approve(workspaceId, caseId, correctionId));
    }

    /** 单卡片读取的统一工作区隔离守卫。 */
    private InvestigationCase findCase(Workspace workspace, UUID caseId) {
        return investigationCaseRepository.findByWorkspaceIdAndPublicId(workspace.getId(), caseId)
                .orElseThrow(() -> new IllegalArgumentException("调查卡片不存在或不属于当前工作区"));
    }

    /** 列表卡片只展示安全状态与摘要。 */
    public record CaseResponse(
            UUID id, String status, String followUpStatus, String summary, String errorCode, String errorMessage,
            OffsetDateTime followUpStartedAt, OffsetDateTime followUpReminderAt, OffsetDateTime updatedAt) {
        /** 将内部实体投影为公开调查卡片契约。 */
        static CaseResponse from(InvestigationCase source) {
            return new CaseResponse(source.getPublicId(), source.getStatus(), source.getFollowUpStatus(), source.getSummary(),
                    source.getErrorCode(), source.getErrorMessage(), source.getFollowUpStartedAt(),
                    source.getFollowUpReminderAt(), source.getUpdatedAt());
        }
    }

    /** 详情组合快照和提案，减少前端多次拉取造成的状态不一致。 */
    public record CaseDetailResponse(
            CaseResponse investigation,
            List<EvidenceResponse> evidence,
            List<ProposalResponse> proposals,
            List<ExecutionResponse> executions) {
    }

    /** 证据响应只公开冻结快照，不公开内部 case/workspace 键。 */
    public record EvidenceResponse(UUID id, String sourceType, String sourceReference, String title, String content, boolean sufficient, String sourceUrl) {
        /** 快照到 API 的安全投影。 */
        static EvidenceResponse from(InvestigationEvidenceSnapshot source) {
            return new EvidenceResponse(source.getPublicId(), source.getSourceType(), source.getSourceReference(), source.getTitle(), source.getContent(), source.isSufficient(), source.getSourceUrl());
        }
    }

    /** 提案响应不含内部主键、执行人或幂等键。 */
    public record ProposalResponse(UUID id, String action, String title, String rationale, String previewJson, String status) {
        /** 提案实体到 HTTP 契约的显式投影。 */
        static ProposalResponse from(ActionProposal source) {
            return new ProposalResponse(source.getPublicId(), source.getAction().name(), source.getTitle(), source.getRationale(), source.getPreviewJson(), source.getStatus());
        }
    }

    /** 执行响应用于轮询与撤销入口，不暴露内部账户或命令密钥。 */
    public record ExecutionResponse(UUID id, String action, String status, String summary, OffsetDateTime updatedAt) {
        /** 执行实体到公开响应的显式投影。 */
        static ExecutionResponse from(ActionExecution source) {
            return new ExecutionResponse(source.getPublicId(), source.getAction().name(), source.getStatus(), source.getSummary(), source.getUpdatedAt());
        }
    }

    /** 纠错请求只允许固定类型与受控候选文本。 */
    public record CorrectionRequest(@NotNull CorrectionKind kind, @NotBlank String content) {
    }

    /** 发布请求必须显式给出两套评测的基线和候选，禁止“无评测直接发布”。 */

    /** 纠错响应不含内部 case/workspace 键或评测原始答案。 */
    public record CorrectionResponse(UUID id, String kind, String content, String status, OffsetDateTime publishedAt) {
        /** 将候选实体安全投影为 HTTP 契约。 */
        static CorrectionResponse from(ManualCorrection source) {
            return new CorrectionResponse(source.getPublicId(), source.getKind().name(), source.getContent(), source.getStatus(), source.getPublishedAt());
        }
    }
}
