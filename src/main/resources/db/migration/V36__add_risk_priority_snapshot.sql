-- 告警事实不可变，优先级同样须按触发时刻冻结，防止策略变更篡改历史处置顺序。
CREATE TABLE risk_priority_snapshot (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    workspace_id BIGINT NOT NULL,
    alert_id BIGINT NOT NULL UNIQUE,
    score INTEGER NOT NULL,
    level VARCHAR(10) NOT NULL,
    reasons VARCHAR(1000) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 首页风险队列按 Workspace 和分数读取；索引同时隔离租户并避免全表排序。
CREATE INDEX idx_risk_priority_snapshot_workspace_score
    ON risk_priority_snapshot (workspace_id, score DESC, created_at DESC);
