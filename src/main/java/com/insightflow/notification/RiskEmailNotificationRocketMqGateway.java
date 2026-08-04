package com.insightflow.notification;

import java.util.UUID;

/**
 * RocketMQ 的最小发送边界；发布器只依赖“Broker 是否确认”，避免业务状态与 SDK API 耦合。
 */
public interface RiskEmailNotificationRocketMqGateway {

    /** 同步等待 Broker 接收风险邮件 Outbox 的公开标识；异常表示发布器必须保留待重试状态。 */
    void publish(UUID outboxPublicId);
}
