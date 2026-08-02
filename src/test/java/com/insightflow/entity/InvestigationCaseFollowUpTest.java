package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 最小响应闭环不分派责任人，但必须记录首位开始跟进的成员，
 * 让团队可区分“异常已被看见”和“异常仍无人响应”。
 */
class InvestigationCaseFollowUpTest {

    /**
     * 开始跟进不会改变调查本身的取证状态；它是与 queued / pending_review 正交的响应事实。
     */
    @Test
    void recordsFirstMemberWhoStartsFollowUpWithoutChangingInvestigationState() {
        InvestigationCase investigation = InvestigationCase.queued(7L, 11L);
        UUID actor = UUID.randomUUID();

        investigation.startFollowUp(actor);

        assertThat(investigation.getStatus()).isEqualTo("queued");
        assertThat(investigation.getFollowUpStatus()).isEqualTo("in_follow_up");
        assertThat(investigation.getFollowUpByUserPublicId()).isEqualTo(actor);
        assertThat(investigation.getFollowUpStartedAt()).isNotNull();
    }

    /**
     * 后续成员点击跟进不能覆盖首位响应者，避免简化模式意外演变为隐式派单系统。
     */
    @Test
    void preservesFirstFollowUpMemberWhenActionIsRepeated() {
        InvestigationCase investigation = InvestigationCase.queued(7L, 11L);
        UUID firstActor = UUID.randomUUID();
        investigation.startFollowUp(firstActor);

        investigation.startFollowUp(UUID.randomUUID());

        assertThat(investigation.getFollowUpByUserPublicId()).isEqualTo(firstActor);
    }

    /** 未开始跟进的卡片可被提醒一次；开始跟进后不再保持提醒状态。 */
    @Test
    void recordsReminderOnlyWhileFollowUpHasNotStarted() {
        InvestigationCase investigation = InvestigationCase.queued(7L, 11L);

        investigation.markFollowUpReminder();

        assertThat(investigation.getFollowUpReminderAt()).isNotNull();
    }
}
