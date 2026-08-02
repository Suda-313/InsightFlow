package com.insightflow.entity;

/** 人工纠错候选的三种受控类型，均需通过评测门禁后才可发布。 */
public enum CorrectionKind {
    /** 主题别名候选，后续规则版本可消费但不会回写历史分类。 */
    ISSUE_ALIAS,
    /** 分类规则候选，首版仅作为待发布资产，不直接改写运行中规则文件。 */
    RULE_CANDIDATE,
    /** 评测样例候选，用于扩展固定评测集而不是修改生产对话。 */
    EVALUATION_CASE
}
