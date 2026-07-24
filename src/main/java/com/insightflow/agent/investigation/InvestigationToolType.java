package com.insightflow.agent.investigation;

/**
 * P2 对外不可见的只读调查 Tool 类型。
 *
 * <p>枚举是规划器与查询服务之间的白名单：模型文本不能构造任意方法名、SQL 或实体字段，
 * 查询服务也只能执行这些经过审计的聚合读取。</p>
 */
public enum InvestigationToolType {

    /** 读取主题日指标趋势。 */
    ISSUE_TREND,

    /** 读取工作区主题的聚合分布。 */
    TOPIC_DISTRIBUTION,

    /** 读取主题或工作区的告警摘要与基线。 */
    ALERT_HISTORY,

    /** 读取数量、长度均受限的脱敏反馈样本。 */
    SAMPLE_FEEDBACK,

    /** 比较当前窗口与上一等长窗口的聚合指标。 */
    PERIOD_COMPARISON,

    /** 说明版本、活动等来源是否可用，防止将时间相关性伪装成因果。 */
    DATA_AVAILABILITY,

    /** P3 企业知识检索的只读证据类型；不由 P2 调查执行器规划。 */
    KNOWLEDGE_SEARCH
}
