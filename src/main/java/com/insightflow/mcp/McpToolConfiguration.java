package com.insightflow.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 只读 Tool 条件装配入口。
 *
 * <p>Tool 实现类使用 {@code @McpTool} 注解，由 Spring AI MCP Server 扫描注册。
 * 本配置仅在 {@code insightflow.mcp.enabled=true} 时装配，默认关闭以避免暴露只读数据面。</p>
 */
@Configuration
@ConditionalOnProperty(name = "insightflow.mcp.enabled", havingValue = "true")
public class McpToolConfiguration {
}
