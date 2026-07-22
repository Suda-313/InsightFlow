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
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackEventRepository;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AgentAnalysisScheduler 单元测试。
 */
class AgentAnalysisSchedulerTest {

    private final CellAnalysisAgent cellAnalysisAgent = mock(CellAnalysisAgent.class);
    private final DataCellRepository dataCellRepository = mock(DataCellRepository.class);
    private final CellIssueRepository cellIssueRepository = mock(CellIssueRepository.class);
    private final FeedbackEventRepository feedbackEventRepository = mock(FeedbackEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void skipsWhenDisabled() {
        AgentAnalysisScheduler scheduler = new AgentAnalysisScheduler(
                cellAnalysisAgent, dataCellRepository, cellIssueRepository,
                feedbackEventRepository, objectMapper, false);

        scheduler.onProjectionCompleted(new ProjectionCompletedEvent(this, "1", "7"));

        verifyNoInteractions(cellAnalysisAgent);
        verifyNoInteractions(dataCellRepository);
    }

    @Test
    void analyzesEachCellWhenEnabled() throws Exception {
        AgentAnalysisScheduler scheduler = new AgentAnalysisScheduler(
                cellAnalysisAgent, dataCellRepository, cellIssueRepository,
                feedbackEventRepository, objectMapper, true);

        DataCell cell1 = DataCell.of(7L, 1L, OffsetDateTime.now(), OffsetDateTime.now(), "stream_end", 10, 100);
        setId(cell1, 100L);

        when(dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(1L, 7L))
                .thenReturn(List.of(cell1));
        when(cellIssueRepository.findByDataCellId(100L)).thenReturn(List.of());
        when(feedbackEventRepository.findAllById(List.of())).thenReturn(List.of());

        scheduler.onProjectionCompleted(new ProjectionCompletedEvent(this, "1", "7"));

        verify(dataCellRepository).findByWorkspaceProjectionIdAndWorkspaceId(1L, 7L);
    }

    private void setId(Object target, long id) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(target, id);
    }
}