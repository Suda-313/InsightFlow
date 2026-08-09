-- 调查窗口必须在首次规划后冻结到 InvestigationCase：AsyncTask payload 不可更新，不能承载 Planner 回写结果。
-- JSONB 仅保存受控枚举、服务端计算的时间边界和限长审计原因；不保存 Prompt、原始反馈或模型思维链。
ALTER TABLE investigation_case
    ADD COLUMN plan_json JSONB;
