package com.insightflow.entity;

/**
 * 人工金标数据集生命周期。
 *
 * <p>一旦 {@link #PUBLISHED} 或 {@link #FROZEN}，题目、证据与断言不可原地修改；变更必须创建新的
 * {@code dataset_version}，以保证历史评测批次可复核。</p>
 */
public enum RagGoldDatasetStatus {

    /** 草稿态，可追加题目与证据；不可被 Runner 加载。 */
    DRAFT,

    /** 已发布，内容冻结并计算 checksum；Runner 可加载。 */
    PUBLISHED,

    /** 自 PUBLISHED 升级；记录 frozen_at，专用于质量门禁。 */
    FROZEN
}
