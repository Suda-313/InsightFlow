package com.insightflow.service.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pack 级 LLM Topic Skill 的全局开关与门控参数。
 *
 * <p>须与 Pack {@code pack.toml} 中的 {@code topic_llm_skill_enabled} 同时为 true 才会在投影中
 * 调用 LLM；默认关闭，避免无密钥环境或成本未评估时误触发模型调用。</p>
 */
@Component
public class TopicLlmSkillProperties {

    /** 全局主开关；Pack 级开关为 false 时即使此处为 true 也不调用 LLM。 */
    private final boolean enabled;
    /** LLM 输出置信度低于此阈值时仍写 topic_general，但会把置信度写入标注行供观测。 */
    private final double confidenceThreshold;
    /** 归一文本长度不足时跳过 LLM，避免过短好评/表情浪费 Token。 */
    private final int minTextLength;

    public TopicLlmSkillProperties(
            @Value("${insightflow.analysis.topic-llm-skill.enabled:false}") boolean enabled,
            @Value("${insightflow.analysis.topic-llm-skill.confidence-threshold:0.7}") double confidenceThreshold,
            @Value("${insightflow.analysis.topic-llm-skill.min-text-length:15}") int minTextLength) {
        this.enabled = enabled;
        this.confidenceThreshold = confidenceThreshold;
        this.minTextLength = minTextLength;
    }

    public boolean enabled() {
        return enabled;
    }

    public double confidenceThreshold() {
        return confidenceThreshold;
    }

    public int minTextLength() {
        return minTextLength;
    }
}
