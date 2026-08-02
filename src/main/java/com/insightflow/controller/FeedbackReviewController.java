package com.insightflow.controller;

import com.insightflow.entity.FeedbackEvent;
import com.insightflow.entity.FeedbackReviewCandidate;
import com.insightflow.entity.Workspace;
import com.insightflow.entity.CorrectionKind;
import com.insightflow.entity.ManualCorrection;
import com.insightflow.correction.CorrectionCommandService;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.security.WorkspaceAccessService;
import com.insightflow.service.analysis.FeedbackReviewCandidateService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 多主题和混合情绪的人工复核 API。
 *
 * <p>Controller 只处理公开 UUID；服务层执行 Workspace 和角色校验。确认或忽略只
 * 改变候选状态，不提供改写规则、历史链接或趋势指标的 HTTP 路径。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/feedback-reviews")
public class FeedbackReviewController {

    private final FeedbackReviewCandidateService candidateService;
    private final WorkspaceAccessService accessService;
    private final FeedbackEventRepository feedbackEvents;
    private final CorrectionCommandService correctionCommandService;

    /** 显式依赖只读样本仓储，样本读取也必须按 Workspace 再过滤。 */
    public FeedbackReviewController(FeedbackReviewCandidateService candidateService,
                                    WorkspaceAccessService accessService,
                                    FeedbackEventRepository feedbackEvents,
                                    CorrectionCommandService correctionCommandService) {
        this.candidateService = candidateService;
        this.accessService = accessService;
        this.feedbackEvents = feedbackEvents;
        this.correctionCommandService = correctionCommandService;
    }

    /** 返回待复核列表；样本来自既有脱敏事件，不暴露内部事件键。 */
    @GetMapping
    public List<CandidateResponse> pending(@PathVariable UUID workspaceId) {
        Workspace workspace = accessService.requireRead(workspaceId);
        return candidateService.pending(workspaceId).stream()
                .map(candidate -> {
                    FeedbackEvent event = feedbackEvents
                            .findByIdAndWorkspaceId(candidate.getFeedbackEventId(), workspace.getId())
                            .orElse(null);
                    String sampleText = event != null ? event.getSanitizedText() : "样本不可用";
                    return CandidateResponse.from(candidate, sampleText, event);
                })
                .toList();
    }

    /** 人工确认建议，不改变已发布规则或历史统计。 */
    @PostMapping("/{candidateId}/confirm")
    public CandidateResponse confirm(@PathVariable UUID workspaceId, @PathVariable UUID candidateId) {
        FeedbackReviewCandidate candidate = candidateService.confirm(workspaceId, candidateId);
        return CandidateResponse.from(candidate, sample(candidate), event(candidate).orElse(null));
    }

    /** 人工忽略候选，保留终态供后续审计。 */
    @PostMapping("/{candidateId}/ignore")
    public CandidateResponse ignore(@PathVariable UUID workspaceId, @PathVariable UUID candidateId) {
        FeedbackReviewCandidate candidate = candidateService.ignore(workspaceId, candidateId);
        return CandidateResponse.from(candidate, sample(candidate), event(candidate).orElse(null));
    }

    /**
     * 人工可提交新的主题候选，但它进入既有纠错候选流程，不能直接创建目录或改写规则。
     */
    @PostMapping("/new-topic")
    public NewTopicResponse submitNewTopic(@PathVariable UUID workspaceId,
                                           @Valid @RequestBody NewTopicRequest request) {
        ManualCorrection correction = correctionCommandService.submit(workspaceId, null,
                CorrectionKind.RULE_CANDIDATE, request.content());
        return new NewTopicResponse(correction.getPublicId(), correction.getStatus());
    }

    /** 再次按 Workspace 读取样本，避免命令响应绕过行级隔离。 */
    private String sample(FeedbackReviewCandidate candidate) {
        return event(candidate).map(FeedbackEvent::getSanitizedText).orElse("样本不可用");
    }

    private java.util.Optional<FeedbackEvent> event(FeedbackReviewCandidate candidate) {
        return feedbackEvents.findByIdAndWorkspaceId(candidate.getFeedbackEventId(), candidate.getWorkspaceId());
    }

    /** 对外契约含候选 public_id、受控建议、脱敏样本及反馈发生时间/来源；不含内部主键。 */
    public record CandidateResponse(UUID id, String reasonCode, String suggestedIssueKey,
                                    String suggestedSentiment, String sampleText, String status,
                                    OffsetDateTime createdAt, OffsetDateTime resolvedAt,
                                    OffsetDateTime feedbackOccurredAt, String sourceKind) {
        static CandidateResponse from(FeedbackReviewCandidate source, String sampleText, FeedbackEvent event) {
            return new CandidateResponse(
                    source.getPublicId(),
                    source.getReasonCode(),
                    source.getSuggestedIssueKey(),
                    source.getSuggestedSentiment(),
                    sampleText,
                    source.getStatus(),
                    source.getCreatedAt(),
                    source.getResolvedAt(),
                    event != null ? event.getOccurredAt() : null,
                    event != null ? event.getSourceKind() : null);
        }
    }

    /** 请求仅接受简短人工描述，禁止自由 JSON 或原始评论批量写入。 */
    public record NewTopicRequest(@NotBlank String content) {
    }

    /** 新主题候选只返回公开 ID 与待复核状态，不暴露内部纠错记录。 */
    public record NewTopicResponse(UUID id, String status) {
    }
}
