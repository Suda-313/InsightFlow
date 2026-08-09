package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 验证调查计划只可在首次规划时冻结，不能被重试 Worker 改写。 */
class InvestigationCasePlanTest {

    /** 此测试会在计划字段可被第二次覆盖时失败，直接保护可复现性。 */
    @Test
    void freezesPlanOnceAndRejectsLaterOverwrite() {
        InvestigationCase investigation = InvestigationCase.queued(7L, 9L);
        String frozenPlan = "{\"selection\":\"WEEKLY\",\"anchorTime\":\"2026-08-08T00:00:00Z\"}";

        investigation.freezePlan(frozenPlan);

        assertThat(investigation.getPlanJson()).isEqualTo(frozenPlan);
        assertThatThrownBy(() -> investigation.freezePlan("{\"selection\":\"SHORT_TERM\"}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
