-- 风险与调查事实提交时同步留下邮件投递意图；应用宕机后可从此表恢复发布，避免直接发信造成通知静默丢失。
CREATE TABLE risk_email_notification_outbox (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    workspace_id BIGINT NOT NULL,
    alert_id BIGINT NOT NULL,
    -- 调查卡片由提交后监听器异步创建；Outbox 必须先随 Alert 事务落库，消费者随后回读卡片。
    investigation_public_id UUID,
    status VARCHAR(20) NOT NULL,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    sent_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (workspace_id, alert_id)
);

-- 发布器只扫描到期的待投递记录；复合索引同时保证多 Workspace 环境下的有序领取效率。
CREATE INDEX idx_risk_email_notification_outbox_publishable
    ON risk_email_notification_outbox (status, next_attempt_at, created_at);
