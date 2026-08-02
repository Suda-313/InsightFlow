package com.insightflow.service.analysis;

import java.util.List;
import java.util.Optional;

/**
 * Pack 级 LLM Topic Skill 端口；仅在规则零命中且门控通过时由 {@link PackTopicClassifier} 调用。
 *
 * <p>实现不得创建 catalog 外的新 canonical_key，不得改写 Pack 规则或历史 link；
 * 无 API 密钥或未启用时由 {@link NoOpTopicPackTopicLlmSkill} 空实现。</p>
 */
public interface TopicPackTopicLlmSkill {

    /**
     * 在 Pack 议题目录约束下对单条归一文本分类。
     *
     * @param normalizedText 已归一化评论
     * @param pack           当前 Workspace 绑定的 Pack（含 catalog 白名单）
     * @return 模型结果；调用失败或未装配 LLM 时返回 empty
     */
    Optional<TopicPackTopicLlmResultDto> classify(String normalizedText, TopicPackLoader pack);

    /** 供编排层写入标注行的 Prompt 版本；无 LLM 时为 null。 */
    String promptVersion();

    /**
     * LLM 层使用的轻量结果，与 agent.dto 解耦以便 analysis 包不依赖 Spring AI。
     */
    record TopicPackTopicLlmResultDto(String canonicalKey, double confidence) {
    }
}
