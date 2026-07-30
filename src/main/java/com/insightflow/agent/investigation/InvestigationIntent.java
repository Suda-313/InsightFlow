package com.insightflow.agent.investigation;

/**
 * 当前聊天问题可被稳定识别的调查意图。
 *
 * <p>该枚举只描述查询目标，不携带数据库字段、写操作或模型自由文本；新增意图前必须同步定义
 * 最少 Tool 集合和金标测试，避免意图数量增长后悄悄扩大数据访问范围。</p>
 */
public enum InvestigationIntent {

    /** 解释某主题在一段时间内的数量变化。 */
    TREND_EXPLANATION,

    /** 调查暴增、异常或告警，需要趋势、告警与样本三类证据交叉验证。 */
    ANOMALY_INVESTIGATION,

    /** 比较本周、上周或用户明确给出的两个时间范围。 */
    PERIOD_COMPARISON,

    /** 比较版本前后表现；没有版本事件来源时必须显式返回数据不足。 */
    VERSION_COMPARISON,

    /** 生成运营周报或专题摘要，只读取聚合事实。 */
    REPORT_GENERATION,

    /** 查询 L2 表达分布、L2→L1 交叉分布或 L2×L1 样本等表达层问题。 */
    EXPRESSION_INQUIRY,

    /** 未匹配高风险或高成本查询时的保守默认意图。 */
    GENERAL_INQUIRY
}
