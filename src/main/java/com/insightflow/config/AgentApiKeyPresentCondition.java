package com.insightflow.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Agent 模型运行时的最小启用条件。
 *
 * <p>本地环境可以只使用导入、投影和数据分析，因此必须同时显式启用 Agent 且配置非空密钥，
 * Spring 才能创建模型相关 Bean。这里不校验密钥的有效性；有效性应由真实模型调用返回的受控错误决定，
 * 避免在应用启动阶段发起网络请求或泄露密钥内容。</p>
 *
 * <p>本条件不读取或记录密钥原文，也不负责校验模型服务可用性。密钥存在仅意味着可以完成 Bean 装配；
 * 网络连通性、供应商鉴权和配额不足仍属于模型调用阶段的错误边界。</p>
 */
public class AgentApiKeyPresentCondition implements Condition {

    /**
     * 通过属性解析后的值判断运行时是否应装配 Agent。
     *
     * <p>功能开关关闭时即使环境中残留密钥也不创建模型客户端，防止本地调试无意产生外部调用；
     * 开关启用但密钥缺失、为空或仅为空白时同样不创建任何依赖模型的组件。</p>
     */
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty("spring.ai.openai.api-key");
        // 使用默认 false，确保没有显式配置时基础分析服务始终能启动。
        boolean agentEnabled = context.getEnvironment().getProperty("insightflow.agent.enabled", Boolean.class, false);
        return agentEnabled && StringUtils.hasText(apiKey);
    }
}
