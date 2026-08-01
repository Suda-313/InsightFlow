package com.insightflow.investigation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightflow.entity.InvestigationCase;
import com.insightflow.repository.InvestigationCaseRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 超时提醒只标记尚未开始跟进的卡片，不创建派单或修改调查结论。 */
class FollowUpReminderServiceTest {
    /** 到达 SLA 的卡片应写入站内提醒时间，供首页明确提示。 */
    @Test
    void marksAwaitingCasesThatExceededSla() {
        InvestigationCase investigation = InvestigationCase.queued(7L, 11L);
        InvestigationCaseRepository repository = Mockito.mock(InvestigationCaseRepository.class);
        OffsetDateTime now = OffsetDateTime.parse("2026-07-31T12:00:00Z");
        when(repository.findByFollowUpStatusAndFollowUpReminderAtIsNullAndCreatedAtBefore(
                        "awaiting_follow_up", now.minusMinutes(30)))
                .thenReturn(List.of(investigation));

        new FollowUpReminderService(repository, 30).markOverdue(now);

        verify(repository).save(investigation);
    }
}
