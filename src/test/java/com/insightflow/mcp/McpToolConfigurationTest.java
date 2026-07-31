package com.insightflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** MCP 条件装配：默认关闭时不注册配置类。 */
class McpToolConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpToolConfiguration.class);

    @Test
    void doesNotLoadMcpConfigurationWhenDisabled() {
        contextRunner
                .withPropertyValues("insightflow.mcp.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(McpToolConfiguration.class));
    }

    @Test
    void loadsMcpConfigurationWhenEnabled() {
        contextRunner
                .withPropertyValues("insightflow.mcp.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(McpToolConfiguration.class));
    }
}
