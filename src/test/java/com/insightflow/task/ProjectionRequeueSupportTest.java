package com.insightflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.AsyncTask;
import com.insightflow.entity.ImportFile;
import com.insightflow.entity.WorkspaceProjection;
import com.insightflow.repository.AsyncTaskRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackProjectionAnnotationRepository;
import com.insightflow.repository.ProjectionFileRepository;
import com.insightflow.repository.WorkspaceProjectionRepository;
import com.insightflow.service.analysis.ProjectionFactWiper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectionRequeueSupportTest {

    @Test
    void healthyWhenSucceededWithCellsAndAnnotations() {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        WorkspaceProjectionRepository projectionRepository = mock(WorkspaceProjectionRepository.class);
        ProjectionFileRepository projectionFileRepository = mock(ProjectionFileRepository.class);
        FeedbackProjectionAnnotationRepository annotationRepository = mock(FeedbackProjectionAnnotationRepository.class);
        DataCellRepository dataCellRepository = mock(DataCellRepository.class);
        ProjectionFactWiper wiper = mock(ProjectionFactWiper.class);

        AsyncTask task = AsyncTask.queuedProjection(1L, "key", "{}");
        setId(task, 10L);
        task.markSucceeded("{}");
        WorkspaceProjection projection = WorkspaceProjection.queued(1L, 10L, "rules:v1");
        setId(projection, 6L);

        when(projectionRepository.findByAsyncTaskIdAndWorkspaceId(10L, 1L)).thenReturn(Optional.of(projection));
        when(dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(6L, 1L)).thenReturn(List.of(mock(com.insightflow.entity.DataCell.class)));
        when(annotationRepository.countByWorkspaceProjectionIdAndWorkspaceId(6L, 1L)).thenReturn(5L);

        ProjectionRequeueSupport support = new ProjectionRequeueSupport(
                taskRepository, projectionRepository, projectionFileRepository,
                annotationRepository, dataCellRepository, wiper);

        assertThat(support.isHealthyProjection(1L, task)).isTrue();
    }

    @Test
    void unhealthyWhenSucceededWithoutAnnotations() {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        WorkspaceProjectionRepository projectionRepository = mock(WorkspaceProjectionRepository.class);
        ProjectionFileRepository projectionFileRepository = mock(ProjectionFileRepository.class);
        FeedbackProjectionAnnotationRepository annotationRepository = mock(FeedbackProjectionAnnotationRepository.class);
        DataCellRepository dataCellRepository = mock(DataCellRepository.class);
        ProjectionFactWiper wiper = mock(ProjectionFactWiper.class);

        AsyncTask task = AsyncTask.queuedProjection(1L, "key", "{}");
        setId(task, 10L);
        task.markSucceeded("{}");
        WorkspaceProjection projection = WorkspaceProjection.queued(1L, 10L, "rules:v1");
        setId(projection, 6L);

        when(projectionRepository.findByAsyncTaskIdAndWorkspaceId(10L, 1L)).thenReturn(Optional.of(projection));
        when(dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(6L, 1L)).thenReturn(List.of(mock(com.insightflow.entity.DataCell.class)));
        when(annotationRepository.countByWorkspaceProjectionIdAndWorkspaceId(6L, 1L)).thenReturn(0L);
        when(projectionFileRepository.findByWorkspaceProjectionIdAndWorkspaceId(6L, 1L)).thenReturn(List.of());

        ProjectionRequeueSupport support = new ProjectionRequeueSupport(
                taskRepository, projectionRepository, projectionFileRepository,
                annotationRepository, dataCellRepository, wiper);

        assertThat(support.isHealthyProjection(1L, task)).isFalse();
        support.removeProjectionChain(1L, task);
        verify(projectionRepository).delete(projection);
        verify(taskRepository).delete(task);
        verify(wiper, never()).wipeWorkspaceAnalysisFacts(anyLong(), anyLong());
    }

    private static void setId(Object target, long id) {
        try {
            var field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
