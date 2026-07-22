package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.IssueMetricBucketRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 编排服务：幂等守卫跳过已写投影；空事件返回 false；正常路径写事实并记录窗口。 */
class WorkspaceProjectionExecutionServiceTest {

    @Test
    void skipsWhenFactsAlreadyWritten() {
        WorkspaceProjectionRepository projRepo = mock(WorkspaceProjectionRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        WorkspaceProjection projection = WorkspaceProjection.queued(7L, 21L, "rules:v1");
        when(projRepo.findById(31L)).thenReturn(Optional.of(projection));
        when(cellRepo.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L))
                .thenReturn(List.of(mock(com.insightflow.entity.DataCell.class)));
        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        WorkspaceProjectionExecutionService service = new WorkspaceProjectionExecutionService(
                projRepo, cellRepo, loader, mock(RuleFirstIssueClassifier.class),
                mock(DataCellBuilder.class), mock(ProjectionFactWriter.class), mock(IssueRulesLoader.class),
                mock(MetricBucketService.class), mock(IssueMetricBucketRepository.class),
                mock(EwmaBaselineService.class), mock(AlertDetector.class));

        boolean result = service.execute(31L, 7L);

        assertThat(result).isTrue();
        verify(loader, never()).load(any(), any());
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
        WorkspaceProjectionExecutionService service = new WorkspaceProjectionExecutionService(
                projRepo, cellRepo, loader, mock(RuleFirstIssueClassifier.class),
                mock(DataCellBuilder.class), mock(ProjectionFactWriter.class), mock(IssueRulesLoader.class),
                mock(MetricBucketService.class), mock(IssueMetricBucketRepository.class),
                mock(EwmaBaselineService.class), mock(AlertDetector.class));

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

        ProjectionSourceLoader loader = mock(ProjectionSourceLoader.class);
        OffsetDateTime occurredAt = OffsetDateTime.now();
        EventInput event = new EventInput(1L, occurredAt, "工单", "登录失败");
        when(loader.load(31L, 7L)).thenReturn(List.of(event));

        RuleFirstIssueClassifier classifier = mock(RuleFirstIssueClassifier.class);
        Classification classification = new Classification("login_failure", 1.0, "rule");
        when(classifier.classify("登录失败")).thenReturn(List.of(classification));

        DataCellBuilder dataCellBuilder = mock(DataCellBuilder.class);
        DataCellPlan plan = new DataCellPlan(occurredAt, occurredAt, "stream_end", List.of(event), 10);
        when(dataCellBuilder.split(List.of(event))).thenReturn(List.of(plan));

        ProjectionFactWriter factWriter = mock(ProjectionFactWriter.class);
        IssueRulesLoader rulesLoader = new IssueRulesLoader();
        rulesLoader.load();
        MetricBucketService metricBucketService = mock(MetricBucketService.class);
        IssueMetricBucketRepository metricBucketRepository = mock(IssueMetricBucketRepository.class);
        when(metricBucketRepository.findByWorkspaceProjectionIdAndWorkspaceId(31L, 7L)).thenReturn(List.of());

        WorkspaceProjectionExecutionService service = new WorkspaceProjectionExecutionService(
                projRepo, cellRepo, loader, classifier, dataCellBuilder, factWriter, rulesLoader,
                metricBucketService, metricBucketRepository, mock(EwmaBaselineService.class),
                mock(AlertDetector.class));

        boolean result = service.execute(31L, 7L);

        assertThat(result).isTrue();
        assertThat(projection.getSourceWindowStart()).isNotNull();
        assertThat(projection.getSourceWindowEnd()).isNotNull();
        verify(factWriter).write(anyLong(), anyLong(), anyList(), anyMap(), anyMap());
        verify(metricBucketService).write(anyLong(), anyLong(), anyList(), anyMap(), anyMap());
        verify(projRepo).saveAndFlush(projection);
    }
}
