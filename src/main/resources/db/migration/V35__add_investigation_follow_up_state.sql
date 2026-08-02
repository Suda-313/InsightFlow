-- 调查取证状态与响应状态刻意分列：前者由异步 Worker 推进，后者仅记录人工是否已开始跟进。
-- 不增加派单或组织层级，首期只保留首位响应人和时间，避免将最小闭环演变为工单系统。
ALTER TABLE investigation_case
    ADD COLUMN follow_up_status VARCHAR(30) NOT NULL DEFAULT 'awaiting_follow_up',
    ADD COLUMN follow_up_by_user_public_id UUID,
    ADD COLUMN follow_up_started_at TIMESTAMP WITH TIME ZONE;

-- 历史卡片未曾经过响应流程，迁移后统一视为等待跟进，保证首页统计口径明确且可追溯。
CREATE INDEX idx_investigation_case_follow_up
    ON investigation_case (workspace_id, follow_up_status, updated_at DESC);
