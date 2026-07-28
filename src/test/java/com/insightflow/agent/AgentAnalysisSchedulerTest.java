package com.insightflow.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.agent.event.ProjectionCompletedEvent;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.FeedbackEvent;
import com.insightflow.entity.Workspace;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackEventRepository;
import com.insightflow.repository.WorkspaceRepository;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * AgentAnalysisScheduler 单元测试。
 */
@ExtendWith(OutputCaptureExtension.class)
class AgentAnalysisSchedulerTest {

    private final CellAnalysisAgent cellAnalysisAgent = mock(CellAnalysisAgent.class);
    private final DataCellRepository dataCellRepository = mock(DataCellRepository.class);
    private final CellIssueRepository cellIssueRepository = mock(CellIssueRepository.class);
    private final FeedbackEventRepository feedbackEventRepository = mock(FeedbackEventRepository.class);
    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void skipsWhenDisabled() {
        AgentAnalysisScheduler scheduler = new AgentAnalysisScheduler(
                cellAnalysisAgent, dataCellRepository, cellIssueRepository,
                feedbackEventRepository, workspaceRepository, objectMapper, false);

        scheduler.onProjectionCompleted(new ProjectionCompletedEvent(this, "1", "7"));

        verifyNoInteractions(cellAnalysisAgent);
        verifyNoInteractions(dataCellRepository);
    }

    @Test
    void logsProjectionAndCellCountWhenAnalysisStarts(CapturedOutput output) throws Exception {
        AgentAnalysisScheduler scheduler = new AgentAnalysisScheduler(
                cellAnalysisAgent, dataCellRepository, cellIssueRepository,
                feedbackEventRepository, workspaceRepository, objectMapper, true);

        DataCell cell1 = DataCell.of(7L, 1L, OffsetDateTime.now(), OffsetDateTime.now(), "stream_end", 10, 100);
        setId(cell1, 100L);
        Workspace workspace = new Workspace("test", 1L);
        setId(workspace, 7L);

        when(workspaceRepository.findById(7L)).thenReturn(Optional.of(workspace));
        when(dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(1L, 7L))
                .thenReturn(List.of(cell1));
        when(cellIssueRepository.findByDataCellId(100L)).thenReturn(List.of());
        when(feedbackEventRepository.findAllById(List.of())).thenReturn(List.of());

        scheduler.onProjectionCompleted(new ProjectionCompletedEvent(this, "1", "7"));

        verify(dataCellRepository).findByWorkspaceProjectionIdAndWorkspaceId(1L, 7L);
        org.assertj.core.api.Assertions.assertThat(output)
                .contains("projection_id=1")
                .contains("workspace_id=7")
                .contains("data_cell_count=1");
    }

    private void setId(Object target, long id) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(target, id);
    }
}
