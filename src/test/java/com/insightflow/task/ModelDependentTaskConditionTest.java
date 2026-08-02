package com.insightflow.task;

import com.insightflow.config.AgentApiKeyPresentCondition;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.AnnotatedElementUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps task infrastructure from requiring an optional model client at local startup.
 *
 * <p>Report generation has no deterministic substitute, so its runner and scheduler must only
 * be registered with the same explicit Agent enablement condition as {@code ReportAgent}.</p>
 */
class ModelDependentTaskConditionTest {

    @Test
    void reportTasksUseTheAgentEnablementCondition() {
        assertThat(conditionFor(AnalysisReportTaskRunner.class))
                .contains(AgentApiKeyPresentCondition.class);
        assertThat(conditionFor(AnalysisReportScheduler.class))
                .contains(AgentApiKeyPresentCondition.class);
    }

    private Class<?>[] conditionFor(Class<?> type) {
        Conditional conditional = AnnotatedElementUtils.findMergedAnnotation(type, Conditional.class);
        return conditional == null ? new Class<?>[0] : conditional.value();
    }
}
