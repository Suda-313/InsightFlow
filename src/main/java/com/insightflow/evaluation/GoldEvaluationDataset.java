package com.insightflow.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 首版金标评测集的不可变资源契约。
 *
 * <p>金标集不保存真实用户反馈或线上回答，而是描述固定脱敏场景下每个问题可核对的事实和禁止项，
 * 供 Prompt、模型、检索策略变更前后的离线回归复用。</p>
 */
public record GoldEvaluationDataset(
        /** 数据集版本，用于将一次评测结果与具体样本快照关联。 */
        String version,
        /** 全量评测题；加载器负责校验首版题量和类别覆盖。 */
        @JsonProperty("cases") List<GoldEvaluationCase> cases) {

    /**
     * 防止运行器或调用方意外修改底层 JSON 反序列化集合，保证同一版本样本在一次运行内保持稳定。
     */
    public GoldEvaluationDataset {
        cases = List.copyOf(cases);
    }
}
