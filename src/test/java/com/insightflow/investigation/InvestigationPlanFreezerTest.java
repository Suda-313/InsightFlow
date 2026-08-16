package com.insightflow.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.Alert;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.investigation.window.InvestigationWindowPolicy;
import com.insightflow.investigation.window.InvestigationWindowPlanner;
import com.insightflow.investigation.window.InvestigationWindowResolver;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证创建调查时即冻结基于 Alert 的默认窗口，而非等待可重试 Worker 自行计算。 */
class InvestigationPlanFreezerTest {

    /** 此测试会在计划遗漏锚点、真实窗口或默认选择时失败。 */
    @Test
    void freezesWeeklyPlanFromImmutableAlertBucket() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        InvestigationPlanFreezer freezer = new InvestigationPlanFreezer(
                new InvestigationWindowPolicy(), new InvestigationWindowResolver(), objectMapper);
        Alert alert = Alert.active(7L, 3L, 5L, OffsetDateTime.parse("2026-08-08T00:00:00Z"),
                10, 2, 1, 3, 5, "{}");
        InvestigationCase investigation = InvestigationCase.queued(7L, 9L);

        freezer.freezeDefaultPlan(investigation, alert);

        JsonNode plan = objectMapper.readTree(investigation.getPlanJson());
        assertThat(plan.path("defaultWindowType").asText()).isEqualTo("WEEKLY");
        assertThat(plan.path("finalWindowType").asText()).isEqualTo("WEEKLY");
        assertThat(plan.path("plannerUsed").asBoolean()).isFalse();
        assertThat(plan.path("windows").get(0).path("anchorTime").asText()).isEqualTo("2026-08-08T00:00Z");
        assertThat(plan.path("windows").get(0).path("currentStart").asText()).isEqualTo("2026-08-02T00:00Z");
        assertThat(plan.path("windows").get(0).path("currentEnd").asText()).isEqualTo("2026-08-09T00:00Z");
    }

    /** 合法 Planner 只能改变受控枚举，实际时间边界仍由 Resolver 用 Alert 桶计算。 */
    @Test
    void freezesPlannerSelectedShortTermWindow() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        InvestigationWindowPlanner planner = (alert, defaultSelection) ->
                new InvestigationWindowPlanner.Proposal("SHORT_TERM", "短期突增", null);
        InvestigationPlanFreezer freezer = new InvestigationPlanFreezer(
                new InvestigationWindowPolicy(), new InvestigationWindowResolver(), planner, objectMapper);
        Alert alert = Alert.active(7L, 3L, 5L, OffsetDateTime.parse("2026-08-08T00:00:00Z"),
                80, 2, 1, 12, 5, "{}");
        InvestigationCase investigation = InvestigationCase.queued(7L, 9L);

        freezer.freezePlan(investigation, alert);

        JsonNode plan = objectMapper.readTree(investigation.getPlanJson());
        assertThat(plan.path("plannerWindowType").asText()).isEqualTo("SHORT_TERM");
        assertThat(plan.path("finalWindowType").asText()).isEqualTo("SHORT_TERM");
        assertThat(plan.path("plannerUsed").asBoolean()).isTrue();
        assertThat(plan.path("windows").get(0).path("currentStart").asText()).isEqualTo("2026-08-08T00:00Z");
        assertThat(plan.path("windows").get(0).path("currentEnd").asText()).isEqualTo("2026-08-09T00:00Z");
    }

    /** 非法模型枚举必须冻结默认值，而不是让模型扩大窗口范围或中断任务创建。 */
    @Test
    void fallsBackToDefaultForInvalidPlannerWindowType() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        InvestigationWindowPlanner planner = (alert, defaultSelection) ->
                new InvestigationWindowPlanner.Proposal("LAST_365_DAYS", "不受支持", null);
        InvestigationPlanFreezer freezer = new InvestigationPlanFreezer(
                new InvestigationWindowPolicy(), new InvestigationWindowResolver(), planner, objectMapper);
        Alert alert = Alert.active(7L, 3L, 5L, OffsetDateTime.parse("2026-08-08T00:00:00Z"),
                80, 2, 1, 12, 5, "{}");
        InvestigationCase investigation = InvestigationCase.queued(7L, 9L);

        freezer.freezePlan(investigation, alert);

        JsonNode plan = objectMapper.readTree(investigation.getPlanJson());
        assertThat(plan.path("finalWindowType").asText()).isEqualTo("WEEKLY");
        assertThat(plan.path("plannerUsed").asBoolean()).isFalse();
        assertThat(plan.path("fallbackReason").asText()).isEqualTo("planner_invalid_window_type");
    }

    /** 重试路径发现已有 plan_json 时必须直接复用，不得再次请求 Planner。 */
    @Test
    void doesNotReplanAnAlreadyFrozenCase() {
        AtomicInteger calls = new AtomicInteger();
        InvestigationWindowPlanner planner = (alert, defaultSelection) -> {
            calls.incrementAndGet();
            return new InvestigationWindowPlanner.Proposal("BOTH", "首次选择", null);
        };
        InvestigationPlanFreezer freezer = new InvestigationPlanFreezer(
                new InvestigationWindowPolicy(), new InvestigationWindowResolver(), planner,
                new ObjectMapper().findAndRegisterModules());
        Alert alert = Alert.active(7L, 3L, 5L, OffsetDateTime.parse("2026-08-08T00:00:00Z"),
                80, 2, 1, 12, 5, "{}");
        InvestigationCase investigation = InvestigationCase.queued(7L, 9L);

        freezer.freezePlan(investigation, alert);
        String frozenPlan = investigation.getPlanJson();
        freezer.freezePlan(investigation, alert);

        assertThat(calls).hasValue(1);
        assertThat(investigation.getPlanJson()).isEqualTo(frozenPlan);
    }
}
