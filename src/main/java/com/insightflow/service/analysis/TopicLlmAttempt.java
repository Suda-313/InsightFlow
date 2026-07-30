package com.insightflow.service.analysis;

/**
 * 一次 LLM Topic 补标尝试的追溯快照，写入 feedback_projection_annotation 的可空列。
 *
 * @param promptVersion        本次使用的 Prompt 版本，与 Pack 版本并列冻结
 * @param confidence           模型返回的置信度；低置信时 L1 仍落 topic_general
 * @param accepted             是否因置信度与 catalog 校验通过而替换了 topic_general link
 * @param acceptedCanonicalKey 接受时写入 link 的 L1 键；未接受时为 null
 */
public record TopicLlmAttempt(
        String promptVersion, double confidence, boolean accepted, String acceptedCanonicalKey) {
}
