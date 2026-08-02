-- 提醒是未开始跟进卡片的站内状态，不引入外部渠道、值班表或责任人升级链路。
ALTER TABLE investigation_case
    ADD COLUMN follow_up_reminder_at TIMESTAMP WITH TIME ZONE;

-- 定时扫描按响应状态与创建时间筛选；索引避免周期任务扫描全部历史卡片。
CREATE INDEX idx_investigation_case_follow_up_sla
    ON investigation_case (follow_up_status, created_at)
    WHERE follow_up_status = 'awaiting_follow_up';
