package com.insightflow.service.analysis;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pack L1 分类编排：规则优先，仅对 topic_general 子集可选调用 LLM 补标。
 *
 * <p>不替换 {@link RuleFirstIssueClassifier} 的全路径——规则命中、ambiguous、too_many
 * 仍按 Phase A/B 行为处理；LLM 只在零规则命中且门控通过时介入，低置信仍写
 * {@link TopicPackDefaults#TOPIC_GENERAL_KEY}，并把置信度写入 L2 标注行追溯。</p>
 */
public class PackTopicClassifier {

    private final TopicPackTopicLlmSkill llmSkill;
    private final TopicLlmSkillProperties properties;

    public PackTopicClassifier(TopicPackTopicLlmSkill llmSkill, TopicLlmSkillProperties properties) {
        this.llmSkill = llmSkill;
        this.properties = properties;
    }

    /**
     * 对单条事件完成 L1 分类与可选 LLM 补标。
     *
     * @param normalizedText 归一文本
     * @param expression     已计算的 L2（用于 LLM 门控）
     * @param pack           Workspace 绑定的 Pack
     * @param ruleClassifier 基于 Pack 规则构造的分类器
     */
    public PackTopicClassificationOutcome classify(
            String normalizedText,
            ExpressionClassification expression,
            TopicPackLoader pack,
            RuleFirstIssueClassifier ruleClassifier) {
        List<Classification> ruleClassifications = ruleClassifier.classify(normalizedText);
        String reviewReason = ruleClassifier.reviewReason(normalizedText, ruleClassifications);
        if (!ruleClassifications.isEmpty()) {
            return new PackTopicClassificationOutcome(ruleClassifications, reviewReason, null);
        }

        Optional<TopicLlmAttempt> llmAttempt = tryLlmUpgrade(normalizedText, expression, pack);
        if (llmAttempt.isPresent() && llmAttempt.get().accepted()) {
            TopicLlmAttempt attempt = llmAttempt.get();
            return new PackTopicClassificationOutcome(
                    List.of(new Classification(
                            attempt.acceptedCanonicalKey(), attempt.confidence(), TopicPackDefaults.ASSIGNMENT_LLM)),
                    null,
                    attempt);
        }
        return new PackTopicClassificationOutcome(
                List.of(TopicPackDefaults.generalClassification()), null, llmAttempt.orElse(null));
    }

    private Optional<TopicLlmAttempt> tryLlmUpgrade(
            String normalizedText, ExpressionClassification expression, TopicPackLoader pack) {
        if (!properties.enabled() || !pack.topicLlmSkillEnabled()) {
            return Optional.empty();
        }
        if (!TopicLlmGate.shouldInvokeLlm(expression, normalizedText, properties.minTextLength())) {
            return Optional.empty();
        }
        String promptVersion = llmSkill.promptVersion();
        if (promptVersion == null) {
            return Optional.empty();
        }
        Optional<TopicPackTopicLlmSkill.TopicPackTopicLlmResultDto> llmResult =
                llmSkill.classify(normalizedText, pack);
        if (llmResult.isEmpty()) {
            return Optional.empty();
        }
        TopicPackTopicLlmSkill.TopicPackTopicLlmResultDto result = llmResult.get();
        String acceptedKey = resolveAcceptedKey(result, pack);
        boolean accepted = acceptedKey != null;
        return Optional.of(new TopicLlmAttempt(promptVersion, result.confidence(), accepted, acceptedKey));
    }

    /** 置信度与 catalog 校验通过且非 topic_general 时返回采纳的 canonical_key。 */
    private String resolveAcceptedKey(TopicPackTopicLlmSkill.TopicPackTopicLlmResultDto result, TopicPackLoader pack) {
        if (result.confidence() < properties.confidenceThreshold()) {
            return null;
        }
        Set<String> allowed = pack.topics().stream().map(TopicPackTopic::canonicalKey).collect(Collectors.toSet());
        if (!allowed.contains(result.canonicalKey())) {
            return null;
        }
        if (TopicPackDefaults.TOPIC_GENERAL_KEY.equals(result.canonicalKey())) {
            return null;
        }
        return result.canonicalKey();
    }

    /** 单条 L1 分类出口：分类列表、复核原因、可选 LLM 追溯。 */
    public record PackTopicClassificationOutcome(
            List<Classification> classifications, String reviewReason, TopicLlmAttempt llmAttempt) {
    }
}
