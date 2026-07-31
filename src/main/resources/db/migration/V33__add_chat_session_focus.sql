-- 会话级调查焦点：多轮追问时用于补全指代，避免 planner 与检索拿到孤立的一句话。
-- 只保存上一轮已经产出的确定性结论，不保存模型推理过程或用户原文。
ALTER TABLE chat_session ADD COLUMN focus_topic_key VARCHAR(120);
ALTER TABLE chat_session ADD COLUMN focus_time_window VARCHAR(60);
ALTER TABLE chat_session ADD COLUMN focus_version_label VARCHAR(60);
ALTER TABLE chat_session ADD COLUMN focus_updated_at TIMESTAMPTZ;
