package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 保护人工复核只改变候选状态，绝不静默改写规则或历史主题链接。 */
class FeedbackReviewCandidateTest {

    /** 若状态机允许重复确认或终态回退，人工操作审计就会失真，此测试应失败。 */
    @Test
    void allowsPendingCandidateToBeConfirmedOnlyOnce() {
        FeedbackReviewCandidate candidate = FeedbackReviewCandidate.pending(
                7L, 11L, 13L, "too_many_topics", "bug_network", "mixed");

        candidate.confirm();

        assertThat(candidate.getStatus()).isEqualTo("confirmed");
        assertThatThrownBy(candidate::confirm).isInstanceOf(IllegalStateException.class);
    }
}
