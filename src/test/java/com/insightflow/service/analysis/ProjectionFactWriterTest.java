package com.insightflow.service.analysis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.CellIssue;
import com.insightflow.entity.DataCell;
import com.insightflow.entity.FeedbackIssueLink;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.repository.CellIssueRepository;
import com.insightflow.repository.DataCellRepository;
import com.insightflow.repository.FeedbackIssueLinkRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.assertj.core.api.Assertions;

/** FactWriter 按 Cell 聚合主题计数，并写 link 与 cell_issue；幂等由唯一约束兜底。 */
class ProjectionFactWriterTest {

    @Test
    void writesLinksAndCellIssuesPerCell() {
        FeedbackIssueLinkRepository linkRepo = mock(FeedbackIssueLinkRepository.class);
        DataCellRepository cellRepo = mock(DataCellRepository.class);
        CellIssueRepository cellIssueRepo = mock(CellIssueRepository.class);
        IssueCatalogService catalogService = mock(IssueCatalogService.class);
        when(cellRepo.saveAndFlush(any(DataCell.class))).thenAnswer(inv -> {
            DataCell c = inv.getArgument(0);
            return c;
        });
        IssueCatalog catalog = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(catalogService.findOrCreate(any(), any(), any())).thenReturn(catalog);

        ProjectionFactWriter writer = new ProjectionFactWriter(
                linkRepo, cellRepo, cellIssueRepo, new ObjectMapper(), catalogService);
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        List<EventInput> events = List.of(new EventInput(1L, now, "登录失败"));
        DataCellPlan plan = new DataCellPlan(now, now, "stream_end", events, 5);
        Map<Long, List<Classification>> classifications = Map.of(
                1L, List.of(new Classification("login_failure", 1.0, "rule")));

        writer.write(31L, 7L, List.of(plan), classifications,
                Map.of("login_failure", "登录失败"));

        verify(linkRepo).saveAndFlush(any(FeedbackIssueLink.class));
        verify(cellIssueRepo).saveAndFlush(any(CellIssue.class));

        // Captor 升级：验证 link 携带的 assignment_method / confidence 与 Classification 一致。
        // issueId 来自 IssueCatalog.getId()，mock 下未触发 IDENTITY 故为 null，是已知 mock 副作用，不在此断言。
        ArgumentCaptor<FeedbackIssueLink> linkCaptor = ArgumentCaptor.forClass(FeedbackIssueLink.class);
        verify(linkRepo).saveAndFlush(linkCaptor.capture());
        FeedbackIssueLink savedLink = linkCaptor.getValue();
        Assertions.assertThat(savedLink.getAssignmentMethod()).isEqualTo("rule");
        Assertions.assertThat(savedLink.getConfidence()).isEqualTo(1.0);

        // Captor 升级：验证 cell_issue 的 mention_count 与 sample_event_ids JSON 数组字符串。
        // dataCellId 来自 DataCell.getId()，mock 下同样为 null（mock artifact），不在此断言。
        ArgumentCaptor<CellIssue> cellIssueCaptor = ArgumentCaptor.forClass(CellIssue.class);
        verify(cellIssueRepo).saveAndFlush(cellIssueCaptor.capture());
        CellIssue savedCellIssue = cellIssueCaptor.getValue();
        Assertions.assertThat(savedCellIssue.getMentionCount()).isEqualTo(1);
        Assertions.assertThat(savedCellIssue.getSampleEventIdsJson())
                .contains("[").contains("]");
    }
}
