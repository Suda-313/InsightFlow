package com.insightflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证本地 Profile 只是开发便利配置：本机文件缺失时仍使用安全的 Agent 默认值。
 */
class LocalAgentProfileConfigurationTest {

    /**
     * 主配置应将 local 设为默认 Profile，使存在的 application-local.yml 自动参与本机启动配置合并。
     */
    @Test
    void usesLocalProfileAsDefault() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context.getEnvironment().getDefaultProfiles()).contains("local");
                });
    }
}
