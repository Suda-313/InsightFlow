package com.insightflow.entity;

/**
 * 风险邮件 Outbox 的生命周期；状态描述投递过程，不代表运营人员已阅读或已处置风险。
 */
public enum RiskEmailNotificationOutboxStatus {
    /** 业务事务已经提交，发布器尚未得到 Broker 确认。 */
    PENDING,
    /** RocketMQ 已确认收到消息，等待消费者调用邮件渠道。 */
    PUBLISHED,
    /** 邮件服务已返回成功；极端宕机窗口仍允许 MQ 至少一次重投。 */
    SENT,
    /** 可观察的终止失败状态，供死信排查或人工重驱动使用。 */
    FAILED
}
