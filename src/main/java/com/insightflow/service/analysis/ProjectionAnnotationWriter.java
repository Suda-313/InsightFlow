package com.insightflow.service.analysis;

import com.insightflow.entity.FeedbackProjectionAnnotation;
import com.insightflow.repository.FeedbackProjectionAnnotationRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 在执行事务内写 feedback_projection_annotation；与 {@link ProjectionFactWriter}（L1）
 * 并列但独立成型，因为 L2 标注每 event 恰好 1 行，不依赖 DataCell 切分——不像 L1 的
 * cell_issue 需要按 Cell 聚合计数，L2 只需按事件逐条落库。
 *
 * <p>幂等性依赖唯一约束 (workspace_projection_id, feedback_event_id)：重试时重复
 * INSERT 由约束阻断，与 feedback_issue_link 的幂等策略完全对齐，本类不自行判重。</p>
 */
@Component
public class ProjectionAnnotationWriter {

    private final FeedbackProjectionAnnotationRepository annotationRepository;

    public ProjectionAnnotationWriter(FeedbackProjectionAnnotationRepository annotationRepository) {
        this.annotationRepository = annotationRepository;
    }

    /**
     * 按事件逐条写入 L2 标注；expressionRuleVersion / topicPackId / topicPackVersion
     * 冻结本次投影的规则与 Pack 口径，供后续追溯历史趋势时解释统计变化的原因。
     *
     * @param projectionId          当前投影内部主键
     * @param workspaceId           一级租户隔离键
     * @param events                投影事件列表
     * @param expressionsByEventId  每个事件 id 对应的 L2 分类结果；调用方保证每个 event 都有值
     * @param expressionRuleVersion 本次投影使用的 L2 规则版本
     * @param topicPackId           本次投影绑定的 Topic Pack 标识
     * @param topicPackVersion      本次投影绑定的 Topic Pack 版本
     */
    public void write(Long projectionId, Long workspaceId, List<EventInput> events,
            Map<Long, ExpressionClassification> expressionsByEventId,
            String expressionRuleVersion, String topicPackId, String topicPackVersion) {
        write(projectionId, workspaceId, events, expressionsByEventId, expressionRuleVersion,
                topicPackId, topicPackVersion, Map.of());
    }

    /**
     * 按事件逐条写入 L2 标注；expressionRuleVersion / topicPackId / topicPackVersion
     * 冻结本次投影的规则与 Pack 口径；llmAttemptsByEventId 可选携带 Pack LLM 追溯字段。
     */
    public void write(Long projectionId, Long workspaceId, List<EventInput> events,
            Map<Long, ExpressionClassification> expressionsByEventId,
            String expressionRuleVersion, String topicPackId, String topicPackVersion,
            Map<Long, TopicLlmAttempt> llmAttemptsByEventId) {
        for (EventInput event : events) {
            ExpressionClassification expression = expressionsByEventId.get(event.id());
            if (expression == null) {
                throw new IllegalStateException("Missing expression classification for event " + event.id());
            }
            TopicLlmAttempt llmAttempt = llmAttemptsByEventId.get(event.id());
            String llmPromptVersion = llmAttempt == null ? null : llmAttempt.promptVersion();
            Double llmConfidence = llmAttempt == null ? null : llmAttempt.confidence();
            annotationRepository.saveAndFlush(FeedbackProjectionAnnotation.of(
                    workspaceId, projectionId, event.id(),
                    expression.canonicalKey(), expression.confidence(), expression.mixedExpression(),
                    expressionRuleVersion, topicPackId, topicPackVersion, llmPromptVersion, llmConfidence));
        }
    }
}
