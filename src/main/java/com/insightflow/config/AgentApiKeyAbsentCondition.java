package com.insightflow.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Activates safe local fallbacks whenever the model client is intentionally unavailable.
 *
 * <p>This is the exact inverse of {@link AgentApiKeyPresentCondition}: the two embedding
 * implementations must never be considered together during component scanning.</p>
 */
public class AgentApiKeyAbsentCondition implements Condition {

    /** Delegation keeps the enablement rules for real and fallback clients in one place. */
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !new AgentApiKeyPresentCondition().matches(context, metadata);
    }
}
