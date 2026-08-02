package com.insightflow.service.analysis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insightflow.entity.FeedbackProjectionAnnotation;
import com.insightflow.repository.FeedbackProjectionAnnotationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L2 标注写入器：按事件逐条落库，携带规则/Pack 版本；缺失分类结果视为编程错误直接抛出。 */
class ProjectionAnnotationWriterTest {

    @Test
    void writesOneAnnotationPerEvent() {
        FeedbackProjectionAnnotationRepository repository = mock(FeedbackProjectionAnnotationRepository.class);
        ProjectionAnnotationWriter writer = new ProjectionAnnotationWriter(repository);
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        List<EventInput> events = List.of(new EventInput(1L, now, "工单", "希望优化匹配"));
        Map<Long, ExpressionClassification> expressions = Map.of(
                1L, new ExpressionClassification("expr_suggestion", 1.0, false));

        writer.write(31L, 7L, events, expressions, "platform:expression:v1", "game-chaoziran", "game-chaoziran:v1");

        ArgumentCaptor<FeedbackProjectionAnnotation> captor = ArgumentCaptor.forClass(FeedbackProjectionAnnotation.class);
        verify(repository).saveAndFlush(captor.capture());
        FeedbackProjectionAnnotation saved = captor.getValue();
        assertThat(saved.getWorkspaceId()).isEqualTo(7L);
        assertThat(saved.getWorkspaceProjectionId()).isEqualTo(31L);
        assertThat(saved.getFeedbackEventId()).isEqualTo(1L);
        assertThat(saved.getPrimaryExpression()).isEqualTo("expr_suggestion");
        assertThat(saved.getExpressionMethod()).isEqualTo("rule");
        assertThat(saved.getExpressionRuleVersion()).isEqualTo("platform:expression:v1");
        assertThat(saved.getTopicPackId()).isEqualTo("game-chaoziran");
        assertThat(saved.getTopicPackVersion()).isEqualTo("game-chaoziran:v1");
    }

    @Test
    void writesLlmMetadataWhenProvided() {
        FeedbackProjectionAnnotationRepository repository = mock(FeedbackProjectionAnnotationRepository.class);
        ProjectionAnnotationWriter writer = new ProjectionAnnotationWriter(repository);
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        List<EventInput> events = List.of(new EventInput(1L, now, "工单", "匹配太慢了"));
        Map<Long, ExpressionClassification> expressions = Map.of(
                1L, new ExpressionClassification("expr_complaint", 1.0, false));
        Map<Long, TopicLlmAttempt> llmAttempts = Map.of(
                1L, new TopicLlmAttempt("pack-topic:v1", 0.42, false, null));

        writer.write(31L, 7L, events, expressions, "platform:expression:v1", "game-chaoziran",
                "game-chaoziran:v2", llmAttempts);

        ArgumentCaptor<FeedbackProjectionAnnotation> captor = ArgumentCaptor.forClass(FeedbackProjectionAnnotation.class);
        verify(repository).saveAndFlush(captor.capture());
        FeedbackProjectionAnnotation saved = captor.getValue();
        assertThat(saved.getTopicLlmPromptVersion()).isEqualTo("pack-topic:v1");
        assertThat(saved.getTopicLlmConfidence()).isEqualTo(0.42);
    }

    /** 编排层必须为每个事件都提供分类结果；缺失属于编程错误，直接暴露而不是静默跳过写半条事实。 */
    @Test
    void throwsWhenExpressionMissingForEvent() {
        FeedbackProjectionAnnotationRepository repository = mock(FeedbackProjectionAnnotationRepository.class);
        ProjectionAnnotationWriter writer = new ProjectionAnnotationWriter(repository);
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        List<EventInput> events = List.of(new EventInput(1L, now, "工单", "缺失分类"));

        assertThatThrownBy(() -> writer.write(31L, 7L, events, Map.of(), "v1", "pack", "pack:v1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
