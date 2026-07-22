package com.insightflow.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.insightflow.agent.event.ProjectionCompletedEvent;
import com.insightflow.entity.DataCell;
import com.insightflow.repository.DataCellRepository;
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

    @Test
    void skipsWhenDisabled() {
        AgentAnalysisScheduler scheduler = new AgentAnalysisScheduler(
                cellAnalysisAgent, dataCellRepository, false);

        scheduler.onProjectionCompleted(new ProjectionCompletedEvent(this, "1", "7"));

        verifyNoInteractions(cellAnalysisAgent);
        verifyNoInteractions(dataCellRepository);
    }

    @Test
    void analyzesEachCellWhenEnabled() throws Exception {
        AgentAnalysisScheduler scheduler = new AgentAnalysisScheduler(
                cellAnalysisAgent, dataCellRepository, true);

        DataCell cell1 = DataCell.of(7L, 1L, OffsetDateTime.now(), OffsetDateTime.now(), "stream_end", 10, 100);
        setId(cell1, 100L);
        DataCell cell2 = DataCell.of(7L, 1L, OffsetDateTime.now(), OffsetDateTime.now(), "count_limit", 20, 200);
        setId(cell2, 101L);

        when(dataCellRepository.findByWorkspaceProjectionIdAndWorkspaceId(1L, 7L))
                .thenReturn(List.of(cell1, cell2));

        scheduler.onProjectionCompleted(new ProjectionCompletedEvent(this, "1", "7"));

        verify(cellAnalysisAgent).analyze("cell_100");
        verify(cellAnalysisAgent).analyze("cell_101");
    }

    private void setId(Object target, long id) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(target, id);
    }
}