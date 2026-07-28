package com.insightflow.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/** 验证金标评测运行器依赖的确定性评分器可被 Spring 自动装配。 */
class EvaluationCaseScorerSpringRegistrationTest {

    @Test
    void registersEvaluationCaseScorerAsSpringBean() {
        // GoldEvaluationRunner 以构造器注入评分器，缺失组件标记会阻断整个应用启动。
        assertThat(EvaluationCaseScorer.class.isAnnotationPresent(Component.class)).isTrue();
    }
}
