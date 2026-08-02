package com.insightflow.service.analysis;

import java.util.List;

/**
 * 主题分类端口；规则优先 {@link RuleFirstIssueClassifier} 为默认路径，
 * Pack 级 {@link TopicPackTopicLlmSkill} 仅补 topic_general 子集（Phase C）。
 */
public interface IssueClassifier {

    /** 对已归一文本分类，返回 0..2 个主题关联；空列表表示 unclassified。 */
    List<Classification> classify(String normalizedText);
}
