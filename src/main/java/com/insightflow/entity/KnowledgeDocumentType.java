package com.insightflow.entity;

/**
 * P3 明确支持的企业知识类型。
 *
 * <p>类型既用于上传时的业务校验，也用于受控检索计划的优先级过滤；首版不开放任意字符串分类，
 * 避免文档元数据逐渐失去可评测语义。</p>
 */
public enum KnowledgeDocumentType {

    /** 游戏或产品版本发布说明。 */
    RELEASE_NOTE,

    /** 已确认的缺陷、影响范围和临时处理方式。 */
    KNOWN_ISSUE,

    /** 客服、运营或值班人员可以执行的标准处理流程。 */
    SUPPORT_SOP,

    /** 舆情识别、分级、回应与复盘的方法手册。 */
    SENTIMENT_PLAYBOOK,

    /** 版本上线、活动、维护、渠道策略等带时效的运营事实记录。 */
    OPERATION_EVENT,

    /** 已完成运营/版本/事故事件的证据化复盘，供后续调查引用。 */
    POSTMORTEM
}
