-- 任务租约让单体内的异步执行可在应用重启后恢复；旧迁移不可改动，因此新增前向字段。
ALTER TABLE async_task
    ADD COLUMN lease_owner VARCHAR(100),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ;

-- 调度器按类型、状态和过期租约查找可领取任务，避免全表扫描影响后续分析任务。
CREATE INDEX idx_async_task_claimable
    ON async_task (task_type, status, lease_expires_at, created_at);
