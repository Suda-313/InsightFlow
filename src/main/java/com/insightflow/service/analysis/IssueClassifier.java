package com.insightflow.service.analysis;

import java.util.List;

/**
 * 主题分类端口；本期 RuleFirstIssueClassifier 是唯一实现，后续 Qwen 实现只处理未命中/歧义。
 *
 * <p>Qwen 实现不得直接创建主题、修改指标或改写 Alert，只能选择已有主题或返回 new_candidate/unclassified。</p>
 */
public interface IssueClassifier {

    /** 对已归一文本分类，返回 0..2 个主题关联；空列表表示 unclassified。 */
    List<Classification> classify(String normalizedText);
}
