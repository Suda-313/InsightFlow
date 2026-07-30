package com.insightflow.entity;

/** 金标断言类型：必要事实与禁止编造项分开评分。 */
public enum RagGoldAssertionType {

    /** 回答中必须覆盖的关键事实。 */
    REQUIRED_FACT,

    /** 回答中不得出现的断言。 */
    FORBIDDEN_CLAIM
}
