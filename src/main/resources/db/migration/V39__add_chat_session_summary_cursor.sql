-- 摘要游标记录 rolling_summary 已消费的最后一条消息 identity；它是处理进度而非领域关联，故不建 FK。
-- 与 rolling_summary 同时更新后，重试只查询该游标之后且已滑出 recent-12 窗口的消息。
ALTER TABLE chat_session
    ADD COLUMN summary_until_message_id BIGINT;

COMMENT ON COLUMN chat_session.summary_until_message_id IS
    'rolling_summary 已消费的最后一条 chat_message 内部主键；与摘要正文在同一事务原子更新';
