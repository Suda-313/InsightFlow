package com.insightflow.entity;

/**
 * 人工金标数据集的训练/验证/冻结划分。
 *
 * <p>冻结集只供发布前门禁运行，开发集允许调参；划分在数据集创建时固定，避免同一题在不同 split 间漂移。</p>
 */
public enum RagGoldDatasetSplit {

    /** 开发调参集；允许反复读取逐题细节辅助定位。 */
    DEVELOPMENT,

    /** 候选方案比较集；不应用于日常 Prompt 微调。 */
    VALIDATION,

    /** 发布门禁集；日志与结果只暴露聚合指标，防止反向调参。 */
    FROZEN
}
