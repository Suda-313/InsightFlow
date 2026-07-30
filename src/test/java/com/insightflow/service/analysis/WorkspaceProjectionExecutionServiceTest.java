package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.insightflow.entity.Workspace;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackProjectionAnnotationRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import com.insightflow.repository.WorkspaceRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 编排服务：幂等守卫跳过已写投影；空事件返回 false；正常路径写事实并记录窗口。 */
class WorkspaceProjectionExecutionServiceTest {

    private static ExpressionRulesLoader expressionRulesLoader() {
        ExpressionRulesLoader loader = new ExpressionRulesLoader();
        loader.load();
        return loader;
    }

    private static TopicPackRegistry topicPackRegistry() {
        TopicPackRegistry registry = new TopicPackRegistry("game-chaoziran");
        registry.load();
        return registry;
    }

    private WorkspaceProjectionExecutionService service(
            WorkspaceProjectionRepository projRepo,
            DataCellRepository cellRepo,
            ProjectionSourceLoader loader,
            DataCellBuilder dataCellBuilder,
            ProjectionFactWriter factWriter,
            MetricBucketService metricBucketService,
            IssueMetricBucketRepository metricBucketRepository,
            ExpressionClassifier expressionClassifier,
            ProjectionAnnotationWriter annotationWriter,
            ExpressionMetricBucketService expressionMetricBucketService,
            WorkspaceRepository workspaceRepository,
            FeedbackProjectionAnnotationRepository annotationRepository,
            ProjectionFactWiper projectionFactWiper,
            PackTopicClassifier packTopicClassifier) {
        return new WorkspaceProjectionExecutionService(
                projRepo, cellRepo, loader, dataCellBuilder, factWriter,
                metricBucketService, metricBucketRepository, mock(EwmaBaselineService.class),
                mock(AlertDetector.class), expressionClassifier, expressionRulesLoader(),
                topicPackRegistry(), workspaceRepository, annotationWriter, expressionMetricBucketService,
                annotationRepository, projectionFactWiper, packTopicClassifier);
    }

    private static PackTopicClassifier defaultPackTopicClassifier() {
        return new PackTopicClassifier(new NoOpTopicPackTopicLlmSkill(), new TopicLlmSkillProperties(false, 0.7, 15));
    }

    @Test
    void skipsWhenFactsAlreadyWritten() {
        WorkspaceProjectionRepository projRepo = mock(WorkspaceProjectionRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        FeedbackProjectionAnnotationRepository annotationRepository = mock(FeedbackProjectionAnnotationRepository.class);
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        when(projRepo.findById(31L)).thenReturn(Optional.of(projection));
        when(cellRepo.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L))
                .thenReturn(List.of(mock(com.insightflow.entity.DataCell.class)));
        when(annotationRepository.countByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(1132L);
        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        WorkspaceProjectionExecutionService service = service(
                projRepo, cellRepo, loader, mock(DataCellBuilder.class), mock(ProjectionFactWriter.class),
                mock(MetricBucketService.class), mock(IssueMetricBucketRepository.class),
                mock(ExpressionClassifier.class), mock(ProjectionAnnotationWriter.class),
                mock(ExpressionMetricBucketService.class), mock(WorkspaceRepository.class),
                annotationRepository, mock(ProjectionFactWiper.class), defaultPackTopicClassifier());

        boolean result = service.execute(31L, 7L);

        assertThat(result).isTrue();
        verify(loader, never()).load(any(), any());
    }

    /** 仅有 L1 data_cell、无 L2 标注时视为半完成，清事实后应重新加载源事件。 */
    @Test
    void rewindsPartialProjectionMissingAnnotations() {
        WorkspaceProjectionRepository projRepo = mock(WorkspaceProjectionRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        FeedbackProjectionAnnotationRepository annotationRepository = mock(FeedbackProjectionAnnotationRepository.class);
        ProjectionFactWiper wiper = mock(ProjectionFactWiper.class);
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        when(projRepo.findById(31L)).thenReturn(Optional.of(projection));
        when(cellRepo.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L))
                .thenReturn(List.of(mock(com.insightflow.entity.DataCell.class)));
        when(annotationRepository.countByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(0L);
        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        when(loader.load(31L, 7L)).thenReturn(List.of());
        WorkspaceProjectionExecutionService service = service(
                projRepo, cellRepo, loader, mock(DataCellBuilder.class), mock(ProjectionFactWriter.class),
                mock(MetricBucketService.class), mock(IssueMetricBucketRepository.class),
                mock(ExpressionClassifier.class), mock(ProjectionAnnotationWriter.class),
                mock(ExpressionMetricBucketService.class), mock(WorkspaceRepository.class),
                annotationRepository, wiper, defaultPackTopicClassifier());

        service.execute(31L, 7L);

        verify(wiper).wipeWorkspaceAnalysisFacts(7L, 31L);
        verify(loader).load(31L, 7L);
    }

    @Test
    void returnsFalseWhenNoEvents() {
        WorkspaceProjectionRepository projRepo = mock(WorkspaceProjectionRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        when(projRepo.findById(31L)).thenReturn(Optional.of(projection));
        when(cellRepo.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(List.of());
        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        when(loader.load(31L, 7L)).thenReturn(List.of());
        WorkspaceProjectionExecutionService service = service(
                projRepo, cellRepo, loader, mock(DataCellBuilder.class), mock(ProjectionFactWriter.class),
                mock(MetricBucketService.class), mock(IssueMetricBucketRepository.class),
                mock(ExpressionClassifier.class), mock(ProjectionAnnotationWriter.class),
                mock(ExpressionMetricBucketService.class), mock(WorkspaceRepository.class),
                mock(FeedbackProjectionAnnotationRepository.class), mock(ProjectionFactWiper.class),
                defaultPackTopicClassifier());

        boolean result = service.execute(31L, 7L);

        assertThat(result).isFalse();
    }

    @Test
    void writesFactsAndRecordsWindowWhenEventsPresent() {
        WorkspaceProjectionRepository projRepo = mock(WorkspaceProjectionRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        when(projRepo.findById(31L)).thenReturn(Optional.of(projection));
        when(cellRepo.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(List.of());

        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(workspaceRepository.findById(7L)).thenReturn(Optional.of(new Workspace("test", 1L)));

        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        OffsetDateTime occurredAt = OffsetDateTime.now();
        EventInput event = new EventInput(1L, occurredAt, "工单", "登录不上");
        when(loader.load(31L, 7L)).thenReturn(List.of(event));

        DataCellBuilder dataCellBuilder = mock(DataCellBuilder.class);
        DataCellPlan plan = new DataCellPlan(occurredAt, occurredAt, "stream_end", List.of(event), 10);
        when(dataCellBuilder.split(List.of(event))).thenReturn(List.of(plan));

        ProjectionFactWriter factWriter = mock(ProjectionFactWriter.class);
        MetricBucketService metricBucketService = mock(MetricBucketService.class);
        IssueMetricBucketRepository metricBucketRepository = mock(IssueMetricBucketRepository.class);
        when(metricBucketRepository.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(List.of());

        ExpressionClassifier expressionClassifier = mock(ExpressionClassifier.class);
        when(expressionClassifier.classify("登录不上"))
                .thenReturn(new ExpressionClassification("expr_complaint", 1.0, false));
        ProjectionAnnotationWriter annotationWriter = mock(ProjectionAnnotationWriter.class);
        ExpressionMetricBucketService expressionMetricBucketService = mock(ExpressionMetricBucketService.class);

        FeedbackProjectionAnnotationRepository annotationRepository = mock(FeedbackProjectionAnnotationRepository.class);
        when(annotationRepository.countByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(1L);
        WorkspaceProjectionExecutionService service = service(
                projRepo, cellRepo, loader, dataCellBuilder, factWriter, metricBucketService,
                metricBucketRepository, expressionClassifier, annotationWriter,
                expressionMetricBucketService, workspaceRepository, annotationRepository, mock(ProjectionFactWiper.class),
                defaultPackTopicClassifier());

        boolean result = service.execute(31L, 7L);

        assertThat(result).isTrue();
        assertThat(projection.getSourceWindowStart()).isNotNull();
        verify(factWriter).write(anyLong(), anyLong(), anyList(), anyMap(), anyMap(), anyMap(), anyMap());
        verify(annotationWriter).write(anyLong(), anyLong(), anyList(), anyMap(), any(), eq("game-chaoziran"), any(), anyMap());
        verify(projRepo).saveAndFlush(projection);
    }

    @Test
    void substitutesTopicGeneralWhenClassifierReturnsEmpty() {
        WorkspaceProjectionRepository projRepo = mock(WorkspaceProjectionRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        when(projRepo.findById(31L)).thenReturn(Optional.of(projection));
        when(cellRepo.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(List.of());

        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(workspaceRepository.findById(7L)).thenReturn(Optional.of(new Workspace("test", 1L)));

        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        OffsetDateTime occurredAt = OffsetDateTime.now();
        EventInput event = new EventInput(1L, occurredAt, "工单", "今天天气不错");
        when(loader.load(31L, 7L)).thenReturn(List.of(event));

        DataCellBuilder dataCellBuilder = mock(DataCellBuilder.class);
        DataCellPlan plan = new DataCellPlan(occurredAt, occurredAt, "stream_end", List.of(event), 10);
        when(dataCellBuilder.split(List.of(event))).thenReturn(List.of(plan));

        ProjectionFactWriter factWriter = mock(ProjectionFactWriter.class);
        MetricBucketService metricBucketService = mock(MetricBucketService.class);
        IssueMetricBucketRepository metricBucketRepository = mock(IssueMetricBucketRepository.class);
        when(metricBucketRepository.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(List.of());

        ExpressionClassifier expressionClassifier = mock(ExpressionClassifier.class);
        when(expressionClassifier.classify("今天天气不错")).thenReturn(ExpressionDefaults.otherClassification());

        FeedbackProjectionAnnotationRepository annotationRepository = mock(FeedbackProjectionAnnotationRepository.class);
        when(annotationRepository.countByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(1L);
        WorkspaceProjectionExecutionService service = service(
                projRepo, cellRepo, loader, dataCellBuilder, factWriter, metricBucketService,
                metricBucketRepository, expressionClassifier, mock(ProjectionAnnotationWriter.class),
                mock(ExpressionMetricBucketService.class), workspaceRepository, annotationRepository,
                mock(ProjectionFactWiper.class), defaultPackTopicClassifier());

        service.execute(31L, 7L);

        verify(factWriter).write(eq(31L), eq(7L), anyList(),
                argThat(map -> map.get(1L).size() == 1
                        && TopicPackDefaults.TOPIC_GENERAL_KEY.equals(map.get(1L).get(0).canonicalKey())),
                anyMap(),
                argThat(map -> !map.containsKey(1L)),
                argThat(names -> TopicPackDefaults.TOPIC_GENERAL_NAME.equals(names.get(TopicPackDefaults.TOPIC_GENERAL_KEY))));
    }

    @Test
    void passesLlmAttemptToAnnotationWriter() {
        WorkspaceProjectionRepository projRepo = mock(WorkspaceProjectionRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        when(projRepo.findById(31L)).thenReturn(Optional.of(projection));
        when(cellRepo.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(List.of());

        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(workspaceRepository.findById(7L)).thenReturn(Optional.of(new Workspace("test", 1L)));

        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        OffsetDateTime occurredAt = OffsetDateTime.now();
        EventInput event = new EventInput(1L, occurredAt, "工单", "整体体验一般但匹配总是等很久");
        when(loader.load(31L, 7L)).thenReturn(List.of(event));

        DataCellBuilder dataCellBuilder = mock(DataCellBuilder.class);
        DataCellPlan plan = new DataCellPlan(occurredAt, occurredAt, "stream_end", List.of(event), 10);
        when(dataCellBuilder.split(List.of(event))).thenReturn(List.of(plan));

        ProjectionFactWriter factWriter = mock(ProjectionFactWriter.class);
        MetricBucketService metricBucketService = mock(MetricBucketService.class);
        IssueMetricBucketRepository metricBucketRepository = mock(IssueMetricBucketRepository.class);
        when(metricBucketRepository.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(List.of());

        ExpressionClassification expression = new ExpressionClassification("expr_complaint", 1.0, false);
        ExpressionClassifier expressionClassifier = mock(ExpressionClassifier.class);
        when(expressionClassifier.classify("整体体验一般但匹配总是等很久")).thenReturn(expression);

        Classification llmClassification = new Classification("topic_matchmaking", 0.91, TopicPackDefaults.ASSIGNMENT_LLM);
        TopicLlmAttempt llmAttempt = new TopicLlmAttempt("pack-topic:v1", 0.91, true, "topic_matchmaking");
        PackTopicClassifier packTopicClassifier = mock(PackTopicClassifier.class);
        when(packTopicClassifier.classify(any(), eq(expression), any(), any()))
                .thenReturn(new PackTopicClassifier.PackTopicClassificationOutcome(
                        List.of(llmClassification), null, llmAttempt));

        ProjectionAnnotationWriter annotationWriter = mock(ProjectionAnnotationWriter.class);
        FeedbackProjectionAnnotationRepository annotationRepository = mock(FeedbackProjectionAnnotationRepository.class);
        when(annotationRepository.countByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(1L);

        WorkspaceProjectionExecutionService service = service(
                projRepo, cellRepo, loader, dataCellBuilder, factWriter, metricBucketService,
                metricBucketRepository, expressionClassifier, annotationWriter,
                mock(ExpressionMetricBucketService.class), workspaceRepository, annotationRepository,
                mock(ProjectionFactWiper.class), packTopicClassifier);

        service.execute(31L, 7L);

        verify(annotationWriter).write(eq(31L), eq(7L), anyList(), anyMap(), any(), eq("game-chaoziran"), any(),
                argThat(map -> map.get(1L) != null && map.get(1L).accepted()));
        verify(factWriter).write(eq(31L), eq(7L), anyList(),
                argThat(map -> "topic_matchmaking".equals(map.get(1L).get(0).canonicalKey())),
                anyMap(), anyMap(), anyMap());
    }
}
