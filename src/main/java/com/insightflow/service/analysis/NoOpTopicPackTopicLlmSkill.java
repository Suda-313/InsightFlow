package com.insightflow.service.analysis;

import java.util.Optional;

/**
 * LLM Topic Skill 的空实现；全局或 Pack 开关关闭、或未装配 ChatClient 时使用。
 *
 * <p>保证投影流水线始终可注入同一端口，测试与无密钥本地环境无需 Mock ChatClient。</p>
 */
public class NoOpTopicPackTopicLlmSkill implements TopicPackTopicLlmSkill {

    @Override
    public Optional<TopicPackTopicLlmResultDto> classify(String normalizedText, TopicPackLoader pack) {
        return Optional.empty();
    }

    @Override
    public String promptVersion() {
        return null;
    }
}
