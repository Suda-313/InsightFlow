package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 规则加载器必须把 classpath toml 解析为有序规则，并暴露与 command service 一致的版本号。
 */
class IssueRulesLoaderTest {

    /** 加载种子规则集应得到 8 条有序规则和冻结版本号。 */
    @Test
    void loadsSeedRulesAndVersion() {
        IssueRulesLoader loader = new IssueRulesLoader();
        loader.load();
        assertThat(loader.currentVersion()).isEqualTo("rules:v1");
        assertThat(loader.rules()).hasSize(8);
        assertThat(loader.normalizeMappings()).isNotEmpty();
        assertThat(loader.rules().get(0).canonicalKey()).isEqualTo("login_failure");
        assertThat(loader.rules().get(0).priority()).isEqualTo(90);
    }
}
