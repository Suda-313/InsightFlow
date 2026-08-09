package com.insightflow.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightflow.entity.Alert;
import com.insightflow.entity.InvestigationCase;
import com.insightflow.investigation.window.InvestigationWindowPolicy;
import com.insightflow.investigation.window.InvestigationWindowResolver;
import java.time.OffsetDateTime;
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
        assertThat(plan.path("windows").get(0).path("currentStart").asText()).isEqualTo("2026-08-01T00:00Z");
    }
}
