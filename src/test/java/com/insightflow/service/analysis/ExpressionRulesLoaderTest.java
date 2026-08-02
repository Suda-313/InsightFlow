package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 加载真实 classpath 平台表达规则文件，校验版本号与 5 类齐全。 */
class ExpressionRulesLoaderTest {

    @Test
    void loadsVersionAndFourPositiveRules() {
        ExpressionRulesLoader loader = new ExpressionRulesLoader();

        loader.load();

        assertThat(loader.currentVersion()).isEqualTo("platform:expression:v1");
        // expr_other 是零命中回退，不在规则文件中声明，因此规则文件只需覆盖其余 4 类。
        assertThat(loader.rules()).extracting(ExpressionRule::canonicalKey)
                .containsExactlyInAnyOrder("expr_suggestion", "expr_complaint", "expr_praise", "expr_neutral");
    }
}
